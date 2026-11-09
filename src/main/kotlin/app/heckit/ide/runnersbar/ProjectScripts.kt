package app.heckit.ide.runnersbar

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText

/**
 * Scripts des Projekts als Tasks: `scripts` aus package.json (npm, pnpm, yarn oder bun je nach Lockfile) und composer.json,
 * Ziele aus einem Makefile und Rezepte aus einem justfile. Gesucht wird wie bei [VsCodeTasks] im Projektordner und eine
 * Ebene darunter; je Werkzeug und Ordner eine Gruppe („npm“, „Composer web“ …).
 */
object ProjectScripts {
    private const val KEY_PREFIX = "scripts:"
    private val SKIPPED_FOLDERS = setOf("node_modules", "vendor")

    /** npm-Lifecycle-Scripts: übernehmbar, aber nicht vorausgewählt (laufen sonst von selbst mit). */
    private val NPM_LIFECYCLE = setOf("install", "preinstall", "postinstall", "prepare", "prepublish", "prepublishOnly", "prepack", "postpack")

    /** Make-Ziele: Name, dann `:`, aber keine Zuweisung (`:=`, `::=`) und keine Muster (`%`). */
    private val MAKE_TARGET = Regex("""^([A-Za-z0-9][A-Za-z0-9_.\-/]*)\s*:(?![:=])(.*)$""")

    /** just-Rezepte: optional `@`, Name, Parameter, `:` - keine Zuweisung (`:=`). */
    private val JUST_RECIPE = Regex("""^@?([A-Za-z][A-Za-z0-9_\-]*)(\s[^:]*?)?\s*:(?!=)""")

    fun hasScripts(basePath: String?): Boolean = folders(basePath).any { folder -> FILES.any { folder.resolve(it).isRegularFile() } }

    private val FILES = listOf("package.json", "composer.json", "Makefile", "makefile", "GNUmakefile", "justfile", ".justfile", "Justfile")

    fun find(basePath: String?): List<VsCodeTasks.Task> {
        val base = basePath?.let(Path::of) ?: return emptyList()
        return folders(basePath).flatMap { folder -> tasksIn(base, folder) }
    }

    /** Dateien, aus denen Scripts gelesen werden. */
    fun isScriptFile(name: String): Boolean = name in FILES

    /** Scripts aus genau dieser Datei (z. B. per Rechtsklick), auch tiefer als eine Ebene im Projekt. */
    fun findIn(basePath: String?, file: Path): List<VsCodeTasks.Task> {
        val base = basePath?.let(Path::of) ?: return emptyList()
        val folder = file.parent ?: return emptyList()
        if (!folder.startsWith(base)) return emptyList()
        return tasksIn(base, folder).filter { it.sourceFile == file.name }
    }

    private fun tasksIn(base: Path, folder: Path): List<VsCodeTasks.Task> {
        return run {
            val relative = base.relativize(folder).toString().replace('\\', '/')
            fun group(tool: String) = if (relative.isEmpty()) tool else "$tool ${folder.name}"
            fun task(tool: String, label: String, command: String, description: String? = null, preselected: Boolean = true, unsupported: String? = null, file: String? = null) =
                VsCodeTasks.Task(
                    folder = relative, label = label, command = command, workingDirectory = folder.toString(), env = emptyMap(),
                    unsupported = unsupported, group = group(tool), description = description,
                    keyPrefix = "$KEY_PREFIX${tool.lowercase()}:", preselected = preselected, sourceFile = file,
                )
            safe(folder) { npm(folder) }.map { (command, script, pre) -> task(npmTool(folder), command, command, script, pre, file = "package.json") } +
                safe(folder) { composer(folder) }.map { (name, desc, pre) ->
                    task("Composer", "composer $name", "composer run-script $name", desc, pre, file = "composer.json")
                } +
                safe(folder) { make(folder) }.map { (name, desc) -> task("Make", "make $name", "make $name", desc, file = makefileName(folder)) } +
                safe(folder) { just(folder) }.map { (name, desc, params) ->
                    task("just", "just $name", "just $name", desc, unsupported = params?.let { RunnersBarBundle.message("scripts.unsupported.params", it) }, file = justfileName(folder))
                }
        }
    }

    // --- package.json --------------------------------------------------------------------------------------------------------

    /** Paketmanager nach Lockfile; ohne Lockfile npm. */
    fun npmTool(folder: Path): String = when {
        folder.resolve("pnpm-lock.yaml").isRegularFile() -> "pnpm"
        folder.resolve("yarn.lock").isRegularFile() -> "yarn"
        folder.resolve("bun.lockb").isRegularFile() || folder.resolve("bun.lock").isRegularFile() -> "bun"
        else -> "npm"
    }

    /** Aufruf, Inhalt des Scripts (als Beschreibung), vorausgewählt. */
    private fun npm(folder: Path): List<Triple<String, String?, Boolean>> {
        val file = folder.resolve("package.json").takeIf { it.isRegularFile() } ?: return emptyList()
        val scripts = scripts(file.readText())
        val tool = npmTool(folder)
        return scripts.keys.map { name ->
            // pre<x>/post<x> laufen mit <x> von selbst mit.
            val hook = (name.startsWith("pre") && name.removePrefix("pre") in scripts) || (name.startsWith("post") && name.removePrefix("post") in scripts)
            val body = (scripts[name] as? com.google.gson.JsonElement)?.takeIf { it.isJsonPrimitive }?.asString
            Triple(npmCommand(tool, name), body, !hook && name !in NPM_LIFECYCLE)
        }
    }

    fun npmCommand(tool: String, script: String): String = "$tool run ${quote(script)}"

    // --- composer.json -------------------------------------------------------------------------------------------------------

    private fun composer(folder: Path): List<Triple<String, String?, Boolean>> {
        val file = folder.resolve("composer.json").takeIf { it.isRegularFile() } ?: return emptyList()
        val root = JsonParser.parseString(file.readText()).asJsonObject
        val descriptions = root.getAsJsonObject("scripts-descriptions")
        return scripts(file.readText()).keys.map { name ->
            val description = descriptions?.get(name)?.takeIf { it.isJsonPrimitive }?.asString
            // Ereignisse wie post-install-cmd oder pre-autoload-dump ruft Composer selbst auf.
            val event = name.startsWith("pre-") || name.startsWith("post-")
            Triple(name, description, !event)
        }
    }

    /** `scripts` als Name → Rohwert; Reihenfolge wie in der Datei. */
    private fun scripts(json: String): Map<String, Any> {
        val scripts = JsonParser.parseString(json).asJsonObject.get("scripts")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
        return scripts.entrySet().associate { it.key to it.value }
    }

    // --- Makefile ------------------------------------------------------------------------------------------------------------

    /** Ziele mit Beschreibung (`ziel: ## Text` oder `## Text` in der Zeile darüber); Spezialziele (`.PHONY`) bleiben weg. */
    fun parseMakefile(lines: List<String>): List<Pair<String, String?>> {
        val result = linkedMapOf<String, String?>()
        lines.forEachIndexed { index, line ->
            if (line.startsWith("\t") || line.startsWith(" ")) return@forEachIndexed
            val match = MAKE_TARGET.find(line) ?: return@forEachIndexed
            val rest = match.groupValues[2]
            val description = rest.substringAfter("##", "").trim().ifEmpty { null }
                ?: lines.getOrNull(index - 1)?.takeIf { it.startsWith("##") }?.removePrefix("##")?.trim()?.ifEmpty { null }
            match.groupValues[1].split(Regex("\\s+")).filter { it.isNotEmpty() && !it.contains('%') && !it.contains('$') }
                .forEach { result.putIfAbsent(it, description) }
        }
        return result.map { it.key to it.value }
    }

    private fun makefileName(folder: Path): String? = actualName(folder, listOf("GNUmakefile", "makefile", "Makefile"))

    private fun make(folder: Path): List<Pair<String, String?>> {
        val file = makefileName(folder)?.let(folder::resolve) ?: return emptyList()
        return parseMakefile(file.readLines())
    }

    // --- justfile ------------------------------------------------------------------------------------------------------------

    /**
     * Rezepte mit Beschreibung (Kommentar darüber) und Pflichtparametern (null = keine). Private Rezepte (`_name`,
     * `[private]`) bleiben weg, ebenso `alias`, `set`, `import`, `mod` und Variablen.
     */
    fun parseJustfile(lines: List<String>): List<Triple<String, String?, String?>> {
        val result = mutableListOf<Triple<String, String?, String?>>()
        lines.forEachIndexed { index, line ->
            if (line.isBlank() || line[0].isWhitespace() || line.startsWith("#") || line.startsWith("[")) return@forEachIndexed
            if (Regex("""^(alias|set|import|mod|export)\b""").containsMatchIn(line)) return@forEachIndexed
            if (Regex("""^[A-Za-z_][\w-]*\s*:=""").containsMatchIn(line)) return@forEachIndexed
            val match = JUST_RECIPE.find(line) ?: return@forEachIndexed
            val above = generateSequence(index - 1) { it - 1 }.takeWhile { it >= 0 }.map { lines[it] }
                .takeWhile { it.startsWith("#") || it.startsWith("[") }.toList()
            if (above.any { it.trim() == "[private]" || it.contains("private") && it.startsWith("[") }) return@forEachIndexed
            val description = above.firstOrNull { it.startsWith("#") }?.removePrefix("#")?.trim()?.ifEmpty { null }
            // Parameter ohne Vorgabe (`name`, nicht `name='x'`, nicht `*rest`) müssen angegeben werden.
            val required = match.groupValues[2].trim().split(Regex("\\s+")).filter {
                it.isNotEmpty() && !it.contains('=') && !it.startsWith("*") && !it.startsWith("$")
            }.map { it.removePrefix("+") }
            result += Triple(match.groupValues[1], description, required.joinToString(" ").ifEmpty { null })
        }
        return result.distinctBy { it.first }
    }

    private fun justfileName(folder: Path): String? = actualName(folder, listOf("justfile", ".justfile", "Justfile"))

    /**
     * Echter Dateiname im Ordner: macOS und Windows unterscheiden Groß-/Kleinschreibung nicht, `resolve("makefile")`
     * findet dort auch „Makefile“ - der Name muss aber zur Datei passen (Rechtsklick vergleicht ihn).
     */
    private fun actualName(folder: Path, candidates: List<String>): String? {
        val present = runCatching { Files.list(folder).use { s -> s.map { it.name }.toList() } }.getOrDefault(emptyList()).toSet()
        return candidates.firstOrNull { it in present && folder.resolve(it).isRegularFile() }
    }

    private fun just(folder: Path): List<Triple<String, String?, String?>> {
        val file = justfileName(folder)?.let(folder::resolve) ?: return emptyList()
        return parseJustfile(file.readLines())
    }

    // --- Hilfen --------------------------------------------------------------------------------------------------------------

    private fun quote(arg: String): String = if (arg.all { it.isLetterOrDigit() || it in "-_:./@" }) arg else "'" + arg.replace("'", "'\\''") + "'"

    private fun <T> safe(folder: Path, block: () -> List<T>): List<T> =
        runCatching(block).onFailure { logger<ProjectScripts>().warn("Runners Bar: cannot read scripts in $folder", it) }.getOrDefault(emptyList())

    private fun folders(basePath: String?): List<Path> {
        val base = basePath?.let(Path::of)?.takeIf { it.isDirectory() } ?: return emptyList()
        return listOf(base) + runCatching {
            Files.list(base).use { stream ->
                stream.filter { it.isDirectory() && !it.name.startsWith(".") && it.name !in SKIPPED_FOLDERS }.sorted().toList()
            }
        }.getOrDefault(emptyList())
    }
}
