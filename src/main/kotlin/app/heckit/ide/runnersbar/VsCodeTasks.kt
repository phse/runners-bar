package app.heckit.ide.runnersbar

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Tasks aus .vscode/tasks.json, z. B. eingecheckt von Kollegen, die VS Code nutzen. Gesucht wird im Projektordner und eine
 * Ebene darunter (Projekte mit mehreren Unterprojekten, jedes mit eigener tasks.json).
 */
object VsCodeTasks {
    private const val TASKS_FILE = ".vscode/tasks.json"
    private val SKIPPED_FOLDERS = setOf("node_modules", "vendor")

    /** Task-Runner und ihre Option für eine andere Datei als gulpfile.js usw. */
    /** Befehle, die eine Eingabe oder ein TTY erwarten: Remote-Shells, Datenbank-Clients, Editoren, `read`, `-it`. */
    private val INTERACTIVE = Regex(
        """\b(ssh|sftp|telnet|mysql|psql|redis-cli|mongosh?|sudo|read|vim?|nano|less|top|htop|import-db|import-files)\b""" +
            """|(^|\s)-(it|ti)\b|--interactive\b|--tty\b""",
    )

    private val TASK_RUNNERS = mapOf("gulp" to "--gulpfile", "grunt" to "--gruntfile", "jake" to "--jakefile")

    /**
     * Ein Task, wie er als Shell-Konfiguration ausgeführt würde. [unsupported] nennt den Grund, falls er sich nicht
     * übernehmen lässt (z. B. `${file}` oder nur `dependsOn`).
     */
    data class Task(
        /** Ordner relativ zum Projekt, "" für den Projektordner. */
        val folder: String,
        val label: String,
        val command: String,
        val workingDirectory: String,
        val env: Map<String, String>,
        val unsupported: String? = null,
        /** Zielgruppe; null = nach dem Ordner benannt, siehe [VsCodeTasksImport.groupName]. */
        val group: String? = null,
        /** Im Dialog statt des Befehls angezeigt. */
        val description: String? = null,
        /** Trennt die Quellen (VS Code, DDEV), damit gleich benannte Tasks sich nicht in die Quere kommen. */
        val keyPrefix: String = "",
        /** Im Dialog vorausgewählt. */
        val preselected: Boolean = true,
    ) {
        /** Wiedererkennung über erneute Importe hinweg. */
        val key: String get() = "$keyPrefix$folder/$label"

        /** Braucht ein echtes Terminal (Shells, Tunnel, Rückfragen); sonst läuft er im Run-Fenster, wo Neu starten geht. */
        val needsTerminal: Boolean get() = INTERACTIVE.containsMatchIn(command)
    }

    fun hasTasks(basePath: String?): Boolean = taskFiles(basePath).isNotEmpty()

    fun find(basePath: String?): List<Task> {
        val base = basePath?.let(Path::of) ?: return emptyList()
        return taskFiles(basePath).flatMap { file ->
            val folder = file.parent.parent
            runCatching { parse(file.readText(), folder, base.relativize(folder).toString().replace('\\', '/')) }
                .onFailure { logger<VsCodeTasks>().warn("Runners Bar: cannot read $file", it) }
                .getOrDefault(emptyList())
        }
    }

    private fun taskFiles(basePath: String?): List<Path> {
        val base = basePath?.let(Path::of)?.takeIf { it.isDirectory() } ?: return emptyList()
        val folders = listOf(base) + runCatching {
            Files.list(base).use { stream ->
                stream.filter { it.isDirectory() && !it.name.startsWith(".") && it.name !in SKIPPED_FOLDERS }.sorted().toList()
            }
        }.getOrDefault(emptyList())
        return folders.map { it.resolve(TASKS_FILE) }.filter { it.isRegularFile() }
    }

    /** [folder] ist der Ordner, den VS Code als workspaceFolder öffnen würde (der über .vscode). */
    fun parse(text: String, folder: Path, relativeFolder: String): List<Task> {
        val root = JsonParser.parseString(stripJsonc(text)).asJsonObject
        val globalOptions = root.obj("options")
        return root.array("tasks").mapNotNull { it as? JsonObject }.mapNotNull { raw ->
            val task = withPlatformOverrides(raw)
            val label = task.string("label") ?: task.string("taskName")
                ?: task.string("script")?.let { "npm: $it" }
                ?: task.string("task")?.let { "${task.string("type")}: $it" }
                ?: return@mapNotNull null
            val options = merge(globalOptions, task.obj("options"))
            val variables = Variables(folder)
            fun unsupported(reason: String) = Task(relativeFolder, label, "", folder.toString(), emptyMap(), reason)

            val command = when (val type = task.string("type") ?: "shell") {
                "shell", "process" -> {
                    val executable = task.commandString() ?: return@mapNotNull if (task.has("dependsOn")) {
                        unsupported(RunnersBarBundle.message("vscode.unsupported.dependsOn"))
                    } else {
                        unsupported(RunnersBarBundle.message("vscode.unsupported.noCommand"))
                    }
                    (listOf(executable) + task.array("args").mapNotNull { it.argString() }.map(::quote)).joinToString(" ")
                }
                "npm" -> "npm run " + (task.string("script") ?: return@mapNotNull unsupported(RunnersBarBundle.message("vscode.unsupported.noCommand")))
                // Task-Runner, die VS Code selbst erkennt; npx nimmt die Version aus dem Projekt.
                in TASK_RUNNERS -> {
                    val name = task.string("task") ?: return@mapNotNull unsupported(RunnersBarBundle.message("vscode.unsupported.noCommand"))
                    val file = task.string("file")?.let { " ${TASK_RUNNERS.getValue(type)} ${quote(it)}" }.orEmpty()
                    "npx $type$file ${quote(name)}"
                }
                // Docker-Erweiterung von VS Code; docker-run bleibt außen vor (zu viele Optionen für eine treue Übersetzung).
                "docker-build" -> task.obj("dockerBuild")?.let(::dockerBuild)
                    ?: return@mapNotNull unsupported(RunnersBarBundle.message("vscode.unsupported.noCommand"))
                "docker-compose" -> task.obj("dockerCompose")?.let(::dockerCompose)
                    ?: return@mapNotNull unsupported(RunnersBarBundle.message("vscode.unsupported.noCommand"))
                else -> return@mapNotNull unsupported(RunnersBarBundle.message("vscode.unsupported.type", type))
            }
            val path = if (task.string("type") == "npm") task.string("path") else null
            val cwd = options?.string("cwd") ?: path
            val resolved = listOfNotNull(command, cwd) + options?.obj("env")?.entrySet().orEmpty().map { it.value.asStringOrEmpty() }
            variables.unknownIn(resolved)?.let { return@mapNotNull unsupported(RunnersBarBundle.message("vscode.unsupported.variable", it)) }

            val workingDirectory = cwd?.let { folder.resolve(variables.resolve(it)).normalize().toString() } ?: folder.toString()
            val env = options?.obj("env")?.entrySet().orEmpty().associate { it.key to variables.resolve(it.value.asStringOrEmpty()) }
            Task(relativeFolder, label, variables.resolve(command), workingDirectory, env)
        }
    }

    /** `dockerBuild` eines docker-build-Tasks als `docker build …`. */
    private fun dockerBuild(build: JsonObject): String {
        val parts = mutableListOf("docker", "build")
        build.string("tag")?.let { parts += listOf("-t", quote(it)) }
        build.string("dockerfile")?.let { parts += listOf("-f", quote(it)) }
        build.obj("buildArgs")?.entrySet()?.forEach { (key, value) -> parts += listOf("--build-arg", quote("$key=${value.asStringOrEmpty()}")) }
        build.string("target")?.let { parts += listOf("--target", quote(it)) }
        build.string("platform")?.let { parts += listOf("--platform", quote(it)) }
        if (build.get("pull")?.asBooleanOrNull() == true) parts += "--pull"
        build.obj("labels")?.entrySet()?.filter { it.key != "includeDefaults" }
            ?.forEach { (key, value) -> parts += listOf("--label", quote("$key=${value.asStringOrEmpty()}")) }
        build.string("customOptions")?.let { parts += it }
        parts += quote(build.string("context") ?: ".")
        return parts.joinToString(" ")
    }

    /** `dockerCompose` eines docker-compose-Tasks als `docker compose … up` bzw. `down`. */
    private fun dockerCompose(compose: JsonObject): String? {
        val parts = mutableListOf("docker", "compose")
        compose.array("files").mapNotNull { it.argString() }.forEach { parts += listOf("-f", quote(it)) }
        (compose.array("envFiles").mapNotNull { it.argString() } + listOfNotNull(compose.string("envFile")))
            .forEach { parts += listOf("--env-file", quote(it)) }
        compose.string("projectName")?.let { parts += listOf("-p", quote(it)) }
        val up = compose.obj("up")
        val down = compose.obj("down")
        when {
            up != null -> {
                up.array("profiles").mapNotNull { it.argString() }.forEach { parts += listOf("--profile", quote(it)) }
                parts += "up"
                if (up.get("detached")?.asBooleanOrNull() != false) parts += "-d"
                if (up.get("build")?.asBooleanOrNull() == true) parts += "--build"
                up.string("customOptions")?.let { parts += it }
                parts += up.array("services").mapNotNull { it.argString() }.map(::quote)
            }
            down != null -> {
                parts += "down"
                down.string("removeImages")?.let { parts += listOf("--rmi", quote(it)) }
                if (down.get("removeVolumes")?.asBooleanOrNull() == true) parts += "-v"
                down.string("customOptions")?.let { parts += it }
            }
            else -> return null
        }
        return parts.joinToString(" ")
    }

    private fun JsonElement.asBooleanOrNull(): Boolean? = if (isJsonPrimitive && asJsonPrimitive.isBoolean) asBoolean else null

    /** `osx`, `linux` und `windows` überschreiben die Angaben des Tasks auf dem jeweiligen System. */
    private fun withPlatformOverrides(task: JsonObject): JsonObject {
        val key = when {
            SystemInfo.isMac -> "osx"
            SystemInfo.isWindows -> "windows"
            else -> "linux"
        }
        val override = task.obj(key) ?: return task
        return task.deepCopy().also { copy -> override.entrySet().forEach { (k, v) -> copy.add(k, v) } }
    }

    /** Optionen des Tasks ergänzen die globalen; `env` wird zusammengeführt. */
    private fun merge(global: JsonObject?, own: JsonObject?): JsonObject? {
        if (global == null || own == null) return own ?: global
        val merged = global.deepCopy()
        own.entrySet().forEach { (k, v) -> merged.add(k, v) }
        val env = merge(global.obj("env"), own.obj("env"))
        if (env != null) merged.add("env", env)
        return merged
    }

    private class Variables(private val folder: Path) {
        private val pattern = Regex("""\$\{([^}]+)}""")

        private fun value(name: String): String? = when {
            name == "workspaceFolder" || name == "workspaceRoot" -> folder.toString()
            name == "workspaceFolderBasename" -> folder.name
            name == "userHome" -> System.getProperty("user.home")
            name == "pathSeparator" || name == "/" -> java.io.File.separator
            name.startsWith("env:") -> System.getenv(name.removePrefix("env:")).orEmpty()
            else -> null
        }

        fun resolve(text: String): String = pattern.replace(text) { match -> value(match.groupValues[1]) ?: match.value }

        /** Erste Variable, die sich außerhalb von VS Code nicht auflösen lässt (z. B. `${file}`, `${input:x}`). */
        fun unknownIn(texts: List<String>): String? =
            texts.firstNotNullOfOrNull { text -> pattern.findAll(text).firstOrNull { value(it.groupValues[1]) == null }?.value }
    }

    private fun JsonObject.commandString(): String? = when (val command = get("command")) {
        null -> null
        else -> command.argString()?.takeIf { it.isNotBlank() }
    }

    /** Argumente sind Texte oder `{ "value": …, "quoting": … }`. */
    private fun JsonElement.argString(): String? = when {
        isJsonPrimitive -> asString
        isJsonObject -> asJsonObject.get("value")?.takeIf { it.isJsonPrimitive }?.asString
        else -> null
    }

    private fun quote(arg: String): String =
        if (arg.isNotEmpty() && arg.none { it.isWhitespace() || it in "'\"\\$`;&|<>()*?#" }) arg
        else "'" + arg.replace("'", "'\\''") + "'"

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject
    private fun JsonObject.array(name: String): JsonArray = get(name) as? JsonArray ?: JsonArray()
    private fun JsonElement.asStringOrEmpty(): String = if (isJsonPrimitive) asString else ""

    /** tasks.json ist JSON mit Kommentaren und Kommas am Ende (JSONC): beides entfernen, Strings bleiben unberührt. */
    internal fun stripJsonc(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {
                    val start = i++
                    while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1
                    out.append(text, start, minOf(i + 1, text.length))
                    i++
                }
                c == '/' && text.getOrNull(i + 1) == '/' -> while (i < text.length && text[i] != '\n') i++
                c == '/' && text.getOrNull(i + 1) == '*' -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                }
                c == ',' -> {
                    // Komma am Ende: das nächste Zeichen nach Leerraum und Kommentaren schließt die Liste.
                    val next = text.getOrNull(skipBlank(text, i + 1))
                    if (next != '}' && next != ']') out.append(c)
                    i++
                }
                else -> out.append(c).also { i++ }
            }
        }
        return out.toString()
    }

    /** Position des nächsten Zeichens ab [from], das weder Leerraum noch Kommentar ist. */
    private fun skipBlank(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            i = when {
                text[i].isWhitespace() -> i + 1
                text.startsWith("//", i) -> text.indexOf('\n', i).let { if (it < 0) text.length else it }
                text.startsWith("/*", i) -> text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                else -> return i
            }
        }
        return i
    }
}
