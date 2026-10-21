package app.heckit.ide.runnersbar

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.project.Project
import com.intellij.sh.run.ShConfigurationType
import com.intellij.sh.run.ShRunConfiguration

/** Shell-Script-Konfigurationen für Befehle. Nur aufrufen, wenn [isAvailable] (Shell-Script-Plugin ist optional). */
object RunnersBarShell {
    private const val TYPE_ID = "ShConfigurationType"

    val isAvailable: Boolean get() = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.any { it.id == TYPE_ID }

    /** Legt eine dauerhafte Konfiguration an, die [command] in [workingDirectory] im Run-Fenster (nicht im Terminal) ausführt. */
    fun create(project: Project, command: String, workingDirectory: String?, name: String = command): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        val type = ShConfigurationType.getInstance()
        val settings = runManager.createConfiguration(runManager.suggestUniqueName(name, type), type)
        (settings.configuration as? ShRunConfiguration)?.apply {
            isExecuteScriptFile = false
            isExecuteInTerminal = false
            scriptText = command
            workingDirectory?.let { scriptWorkingDirectory = it }
        }
        runManager.addConfiguration(settings)
        return settings
    }

    /**
     * Legt für einen VS-Code-Task eine Konfiguration an oder aktualisiert [existing]. Neue laufen im Terminal, wenn
     * [inTerminal] (Vorgabe: [VsCodeTasks.Task.needsTerminal]); bei bestehenden bleibt die Einstellung, wie sie ist.
     */
    fun createOrUpdate(
        project: Project,
        task: VsCodeTasks.Task,
        name: String,
        existing: RunnerAndConfigurationSettings?,
        inTerminal: Boolean = task.needsTerminal,
    ): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        val type = ShConfigurationType.getInstance()
        val settings = existing ?: runManager.createConfiguration(runManager.suggestUniqueName(name, type), type)
        (settings.configuration as? ShRunConfiguration)?.apply {
            isExecuteScriptFile = false
            if (existing == null) isExecuteInTerminal = inTerminal
            scriptText = task.command
            scriptWorkingDirectory = task.workingDirectory
            envData = EnvironmentVariablesData.create(task.env, true)
        }
        runManager.addConfiguration(settings)
        return settings
    }

    /** Weicht die Konfiguration von [task] ab (Befehl, Arbeitsverzeichnis, Umgebung)? */
    fun differs(settings: RunnerAndConfigurationSettings, task: VsCodeTasks.Task): Boolean {
        val configuration = settings.configuration as? ShRunConfiguration ?: return false
        return configuration.scriptText != task.command || configuration.scriptWorkingDirectory != task.workingDirectory ||
            configuration.envData.envs != task.env
    }

    /** „Execute in the Terminal“ einer Shell-Script-Konfiguration; null bei anderen Typen oder ohne Shell-Script-Plugin. */
    fun executesInTerminal(settings: RunnerAndConfigurationSettings): Boolean? =
        if (isAvailable) (settings.configuration as? ShRunConfiguration)?.isExecuteInTerminal else null

    /** Schaltet „Execute in the Terminal“ um; addConfiguration lässt die IDE die Änderung speichern. */
    fun setExecuteInTerminal(project: Project, settings: RunnerAndConfigurationSettings, inTerminal: Boolean) {
        val configuration = settings.configuration as? ShRunConfiguration ?: return
        configuration.isExecuteInTerminal = inTerminal
        RunManager.getInstance(project).addConfiguration(settings)
    }

    /** Befehl einer Shell-Script-Konfiguration ohne Skriptdatei; sonst null. */
    fun command(settings: RunnerAndConfigurationSettings): String? {
        if (!isAvailable) return null
        val configuration = settings.configuration as? ShRunConfiguration ?: return null
        return configuration.scriptText.trim().takeIf { !configuration.isExecuteScriptFile && it.isNotEmpty() && '\n' !in it }
    }

    /** Arbeitsverzeichnis einer Shell-Script-Konfiguration mit aufgelösten Makros wie $PROJECT_DIR$; null, wenn keins gesetzt ist. */
    fun workingDirectory(project: Project, settings: RunnerAndConfigurationSettings): String? {
        if (!isAvailable) return null
        val directory = (settings.configuration as? ShRunConfiguration)?.scriptWorkingDirectory?.takeIf { it.isNotBlank() } ?: return null
        // getService statt getInstance: in 2026.1 hat PathMacroManager noch kein Kotlin-Companion.
        return project.getService(PathMacroManager::class.java).expandPath(directory)
    }
}
