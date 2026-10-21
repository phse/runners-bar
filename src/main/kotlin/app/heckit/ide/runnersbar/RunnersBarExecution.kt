package app.heckit.ide.runnersbar

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

object RunnersBarExecution {
    fun execute(settings: RunnerAndConfigurationSettings, debug: Boolean) {
        val executor = if (debug && canDebug(settings)) {
            DefaultDebugExecutor.getDebugExecutorInstance()
        } else {
            DefaultRunExecutor.getRunExecutorInstance()
        }
        ProgramRunnerUtil.executeConfiguration(settings, executor)
    }

    fun canDebug(settings: RunnerAndConfigurationSettings): Boolean =
        ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, settings.configuration) != null
}

/** Merkt sich, welche Konfigurationen gerade laufen. Alle Zugriffe auf dem EDT. */
class RunningTracker(project: Project, parent: Disposable, private val onChange: () -> Unit) {
    /** Laufende Prozesse je Konfiguration, mit ihrer Umgebung für „Neu starten“. */
    private val running = mutableMapOf<String, MutableMap<ProcessHandler, ExecutionEnvironment>>()

    init {
        project.messageBus.connect(parent).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                val id = env.runnerAndConfigurationSettings?.uniqueID ?: return
                onEdt { running.getOrPut(id) { mutableMapOf() }[handler] = env }
            }

            override fun processTerminated(
                executorId: String,
                env: ExecutionEnvironment,
                handler: ProcessHandler,
                exitCode: Int,
            ) {
                val id = env.runnerAndConfigurationSettings?.uniqueID ?: return
                onEdt {
                    running[id]?.let {
                        it.remove(handler)
                        if (it.isEmpty()) running.remove(id)
                    }
                }
            }
        })
    }

    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            block()
            onChange()
        }
    }

    fun isRunning(configId: String): Boolean =
        running[configId]?.keys?.any { !it.isProcessTerminated && !it.isProcessTerminating } == true

    fun stop(configId: String) {
        running[configId]?.keys?.toList()?.forEach { it.destroyProcess() }
    }

    /** Wie „Rerun“ im Run-Fenster: beendet den laufenden Prozess und startet in derselben Umgebung (Run bzw. Debug) neu. */
    fun restart(configId: String) {
        running[configId]?.entries?.lastOrNull { !it.key.isProcessTerminated }?.value?.let(ExecutionUtil::restart)
    }
}
