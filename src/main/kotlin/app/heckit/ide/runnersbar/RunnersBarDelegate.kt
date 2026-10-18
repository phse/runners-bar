package app.heckit.ide.runnersbar

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.ide.actions.runAnything.RunAnythingContext
import com.intellij.ide.actions.runAnything.activity.RunAnythingCommandProvider
import com.intellij.ide.actions.runAnything.activity.RunAnythingProvider
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

/** Gibt Befehle an die Run-Anything-Anbieter der IDE weiter, damit deren eigene Konfigurationen entstehen. */
object RunnersBarDelegate {

    /**
     * Anderer Run-Anything-Anbieter, der den Befehl versteht (z. B. „npm run dev“, Gradle, Maven) – dann entsteht
     * dessen eigene Konfiguration statt eines Shell-Scripts. Der allgemeine Befehlsanbieter zählt nicht.
     */
    @Suppress("UNCHECKED_CAST")
    fun find(dataContext: DataContext, command: String): Pair<Any, RunAnythingProvider<Any>>? =
        RunAnythingProvider.EP_NAME.extensionList.asSequence()
            .filter { it !is RunnersBarRunAnythingProvider && it !is RunAnythingCommandProvider }
            .firstNotNullOfOrNull { provider ->
                val value = runCatching { provider.findMatchingValue(dataContext, command) }.getOrNull() ?: return@firstNotNullOfOrNull null
                value to (provider as RunAnythingProvider<Any>)
            }

    /** Übergibt die Konfiguration, die als Nächstes startet, an [onStarted]; der Anbieter startet sie evtl. erst später. */
    fun keepNextStarted(project: Project, onStarted: (RunnerAndConfigurationSettings) -> Unit) {
        val disposable = Disposer.newDisposable(RunnersBarService.getInstance(project), "runnersBarKeep")
        project.messageBus.connect(disposable).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                val settings = env.runnerAndConfigurationSettings ?: return
                Disposer.dispose(disposable)
                onStarted(settings)
            }
        })
        AppExecutorUtil.getAppScheduledExecutorService().schedule({ Disposer.dispose(disposable) }, 60, TimeUnit.SECONDS)
    }

    /** Datenkontext wie in Run Anything: [directory] als gewähltes Verzeichnis, sonst das Projekt. */
    fun context(project: Project, directory: String? = null): DataContext = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(
            RunAnythingProvider.EXECUTING_CONTEXT,
            directory?.let { RunAnythingContext.RecentDirectoryContext(it) } ?: RunAnythingContext.ProjectContext(project),
        )
        .build()

    /**
     * Wandelt eine Shell-Script-Konfiguration in die Konfiguration des passenden Anbieters um (im selben Verzeichnis): startet den Befehl,
     * ersetzt den Eintrag in der Leiste und entfernt die alte Konfiguration. False, wenn kein Anbieter passt.
     */
    fun convert(project: Project, entry: RunnersBarEntry, settings: RunnerAndConfigurationSettings): Boolean {
        val command = RunnersBarShell.command(settings) ?: return false
        val dataContext = context(project, RunnersBarShell.workingDirectory(project, settings))
        val (value, provider) = find(dataContext, command) ?: return false
        keepNextStarted(project) { RunnersBarService.getInstance(project).replace(entry, settings, it) }
        provider.execute(dataContext, value)
        return true
    }
}
