package app.heckit.ide.runnersbar

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines

/**
 * DDEV-Befehle als Tasks: die üblichen (`ddev start`, `ddev ssh` …) und die eigenen des Projekts aus .ddev/commands. Gesucht
 * wird wie bei [VsCodeTasks] im Projektordner und eine Ebene darunter.
 */
object DdevCommands {
    private const val CONFIG_FILE = ".ddev/config.yaml"
    private const val KEY_PREFIX = "ddev:"
    private val SKIPPED_FOLDERS = setOf("node_modules", "vendor")

    private val STANDARD = listOf(
        "start", "stop", "restart", "describe", "launch", "ssh", "logs -f", "composer install", "import-db", "snapshot",
    )

    fun hasProjects(basePath: String?): Boolean = projectFolders(basePath).isNotEmpty()

    fun find(basePath: String?): List<VsCodeTasks.Task> {
        val base = basePath?.let(Path::of) ?: return emptyList()
        return projectFolders(basePath).flatMap { folder ->
            val relative = base.relativize(folder).toString().replace('\\', '/')
            val group = if (relative.isEmpty()) "DDEV" else "DDEV ${folder.name}"
            fun task(command: String, description: String? = null, preselected: Boolean = true) = VsCodeTasks.Task(
                relative, "ddev $command", "ddev $command", folder.toString(), emptyMap(),
                group = group, description = description, keyPrefix = KEY_PREFIX, preselected = preselected,
            )
            val standard = STANDARD.map { task(it) }
            val custom = customCommands(folder)
                .filter { command -> STANDARD.none { it.substringBefore(' ') == command.name } }
                .map { task(it.name, it.description, !it.generated) }
            standard + custom
        }
    }

    private class Custom(val name: String, val description: String?, val generated: Boolean)

    /**
     * Eigene Befehle aus .ddev/commands/<host|web|db|…>/. Von DDEV mitgelieferte (`#ddev-generated`, z. B. für Datenbank-Tools)
     * sind im Dialog nicht vorausgewählt; Beispiele (*.example) und READMEs bleiben weg.
     */
    private fun customCommands(folder: Path): List<Custom> {
        val commands = folder.resolve(".ddev/commands").takeIf { it.isDirectory() } ?: return emptyList()
        return list(commands).filter { it.isDirectory() }.flatMap(::list)
            .filter { it.isRegularFile() && !it.name.startsWith(".") && !it.name.endsWith(".example") && !it.name.startsWith("README") }
            .distinctBy { it.name }
            .sortedBy { it.name }
            .map { file ->
                val lines = runCatching { file.readLines().take(30) }.getOrDefault(emptyList())
                val description = lines.firstOrNull { it.startsWith("## Description:") }?.substringAfter(':')?.trim()?.ifEmpty { null }
                Custom(file.name, description, lines.any { it.contains("#ddev-generated") })
            }
    }

    private fun projectFolders(basePath: String?): List<Path> {
        val base = basePath?.let(Path::of)?.takeIf { it.isDirectory() } ?: return emptyList()
        val folders = listOf(base) + list(base).filter { it.isDirectory() && !it.name.startsWith(".") && it.name !in SKIPPED_FOLDERS }
        return folders.filter { it.resolve(CONFIG_FILE).isRegularFile() }
    }

    private fun list(folder: Path): List<Path> =
        runCatching { Files.list(folder).use { stream -> stream.sorted().toList() } }.getOrDefault(emptyList())
}
