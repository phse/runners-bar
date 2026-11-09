package app.heckit.ide.runnersbar

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.VirtualFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * `.runners-bar` im Projektordner (JSON, Kommentare erlaubt): Gruppen mit Befehlen, die automatisch als Shell-Konfigurationen
 * in der Leiste landen - z. B. von einem KI-Agenten beim Anlegen eines Projekts geschrieben. Die Datei ist die Quelle:
 * Befehl, Ordner, Umgebung und Terminal werden bei jeder Änderung nachgezogen, entfernte Runner verschwinden wieder.
 *
 * ```
 * { "groups": [ { "name": "Plugin", "runners": [
 *     { "name": "Sandbox", "command": "./gradlew runIde", "terminal": false, "cwd": ".", "env": { "A": "1" } } ] } ] }
 * ```
 */
object RunnersBarFile {
    const val FILE_NAME = ".runners-bar"
    private const val KEY_PREFIX = "runners-bar:"

    /** Ein Runner aus der Datei; [terminal] null = wie bei VS-Code-Tasks am Befehl erkannt. */
    data class Runner(val task: VsCodeTasks.Task, val terminal: Boolean?) {
        val inTerminal: Boolean get() = terminal ?: task.needsTerminal
    }

    fun path(basePath: String?): Path? = basePath?.let { Path.of(it, FILE_NAME) }

    fun read(basePath: String?): List<Runner>? {
        val file = path(basePath)?.takeIf { it.isRegularFile() } ?: return null
        return runCatching { parse(file.readText(), file.parent) }
            .onFailure { logger<RunnersBarFile>().warn("Runners Bar: cannot read $file", it) }
            .getOrNull()
    }

    fun parse(text: String, base: Path): List<Runner> {
        val root = JsonParser.parseString(VsCodeTasks.stripJsonc(text)).asJsonObject
        return root.getAsJsonArray("groups")?.filterIsInstance<JsonObject>().orEmpty().flatMap { group ->
            val groupName = group.string("name")?.takeIf { it.isNotBlank() } ?: RunnersBarGroup.DEFAULT_GROUP
            group.getAsJsonArray("runners")?.filterIsInstance<JsonObject>().orEmpty().mapNotNull { runner ->
                val command = runner.string("command")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = runner.string("name")?.takeIf { it.isNotBlank() } ?: command
                val cwd = runner.string("cwd")?.let { base.resolve(it).normalize() } ?: base
                val env = runner.getAsJsonObject("env")?.entrySet().orEmpty()
                    .filter { it.value.isJsonPrimitive }.associate { it.key to it.value.asString }
                val terminal = runner.get("terminal")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                Runner(
                    VsCodeTasks.Task(
                        folder = groupName, label = name, command = command, workingDirectory = cwd.toString(), env = env,
                        group = groupName, keyPrefix = KEY_PREFIX,
                    ),
                    terminal,
                )
            }
        }
    }

    /** Bringt die Leiste auf den Stand der Datei. Neue Runner kommen in ihre Gruppe, entfernte werden gelöscht. */
    fun sync(project: Project, runners: List<Runner>) {
        if (project.isDisposed || !RunnersBarShell.isAvailable) return
        val service = RunnersBarService.getInstance(project)
        // Gleiche Namen in zwei Gruppen: nur der erste zählt.
        val unique = runners.distinctBy { it.task.key }
        for (runner in unique) {
            // In der IDE immer „Name (Gruppe)“, damit gleiche Namen aus mehreren Gruppen eindeutig sind; die Leiste zeigt
            // innerhalb der Gruppe nur „Name“ (siehe [tabText]).
            val fullName = qualifiedName(runner.task.label, runner.task.group!!)
            var existing = service.importedSettings(runner.task.key)
            // Eine gleichnamige Shell-Konfiguration (z. B. von Hand angelegt) übernehmen statt eine Doppelung daneben anzulegen;
            // eine früher so angelegte Doppelung wird dabei wieder gelöscht.
            val sameName = if (existing == null) adoptable(project, fullName) ?: adoptable(project, runner.task.label) else adoptable(project, fullName)
            if (sameName != null && sameName != existing) {
                existing?.let(service::delete)
                service.markImported(runner.task.key, sameName)
                service.addToGroup(runner.task.group!!, sameName)
                existing = sameName
            }
            if (existing == null) {
                val settings = RunnersBarShell.createOrUpdate(project, runner.task, fullName, null, runner.inTerminal)
                service.markImported(runner.task.key, settings)
                service.addToGroup(runner.task.group!!, settings)
            } else {
                if (RunnersBarShell.differs(existing, runner.task)) RunnersBarShell.createOrUpdate(project, runner.task, existing.name, existing)
                if (RunnersBarShell.executesInTerminal(existing) != runner.inTerminal) {
                    RunnersBarShell.setExecuteInTerminal(project, existing, runner.inTerminal)
                }
                service.rename(existing, fullName)
            }
        }
        val keys = unique.mapTo(HashSet()) { it.task.key }
        service.importedKeys(KEY_PREFIX).filter { it !in keys }.forEach { key ->
            service.importedSettings(key)?.let(service::delete)
            service.forgetImported(key)
        }
    }

    /** Schlüssel, unter dem der Runner [label] aus [group] übernommen ist (siehe [VsCodeTasks.Task.key]). */
    fun key(group: String, label: String): String = "$KEY_PREFIX$group/$label"

    fun qualifiedName(name: String, group: String): String = "$name ($group)"

    private fun adoptable(project: Project, name: String): RunnerAndConfigurationSettings? {
        val service = RunnersBarService.getInstance(project)
        return RunManager.getInstance(project).allSettings.firstOrNull {
            it.name == name && it.type.id == "ShConfigurationType" && !it.isTemporary && !service.isImported(it)
        }
    }

    /** Liest die Datei im Hintergrund und gleicht danach auf dem EDT ab; eine fehlende Datei entfernt die Runner daraus. */
    fun syncLater(project: Project) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val runners = read(project.basePath)
            // Unlesbare Datei (z. B. halb getippt): nichts löschen, auf die nächste Änderung warten.
            if (runners == null && path(project.basePath)?.isRegularFile() == true) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({ sync(project, runners.orEmpty()) }, project.disposed)
        }
    }
}

/** Liest `.runners-bar` beim Öffnen des Projekts und bei jeder Änderung der Datei. */
class RunnersBarFileStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!RunnersBarShell.isAvailable) return
        val runners = withContext(Dispatchers.IO) { RunnersBarFile.read(project.basePath) }
        withContext(Dispatchers.EDT) { if (runners != null) RunnersBarFile.sync(project, runners) }
        val path = RunnersBarFile.path(project.basePath)?.toString()?.replace('\\', '/') ?: return
        project.messageBus.connect(RunnersBarService.getInstance(project)).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path == path }) RunnersBarFile.syncLater(project)
            }
        })
    }
}

private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
