package app.heckit.ide.runnersbar

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.openapi.project.Project
import com.intellij.sh.run.ShConfigurationType
import com.intellij.sh.run.ShRunConfiguration

/** Shell-Script-Konfigurationen für Befehle. Nur aufrufen, wenn [isAvailable] (Shell-Script-Plugin ist optional). */
object RunnersBarShell {
    private const val TYPE_ID = "ShConfigurationType"

    val isAvailable: Boolean get() = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.any { it.id == TYPE_ID }

    /** Legt eine dauerhafte Konfiguration an, die [command] in [workingDirectory] ausführt. */
    fun create(project: Project, command: String, workingDirectory: String?, name: String = command): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        val type = ShConfigurationType.getInstance()
        val settings = runManager.createConfiguration(runManager.suggestUniqueName(name, type), type)
        (settings.configuration as? ShRunConfiguration)?.apply {
            isExecuteScriptFile = false
            scriptText = command
            workingDirectory?.let { scriptWorkingDirectory = it }
        }
        runManager.addConfiguration(settings)
        return settings
    }
}
