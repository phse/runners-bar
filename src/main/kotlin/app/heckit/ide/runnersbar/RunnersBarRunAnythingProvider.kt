package app.heckit.ide.runnersbar

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.runAnything.RunAnythingCache
import com.intellij.ide.actions.runAnything.RunAnythingContext
import com.intellij.ide.actions.runAnything.activity.RunAnythingProvider
import com.intellij.ide.actions.runAnything.activity.RunAnythingProviderBase
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessModuleDir
import com.intellij.openapi.project.guessProjectDir
import javax.swing.Icon

/**
 * Run Anything: „bar <Befehl>“ fragt nach einem Namen, legt eine Konfiguration für den Befehl an (eigene des passenden Anbieters, sonst Shell-Script), nimmt sie in die aktive
 * Gruppe der Runners Bar auf und startet sie. Das Präfix ist in den Einstellungen änderbar (für alle Projekte).
 * Nur registriert, wenn das Shell-Script-Plugin vorhanden ist (siehe runnersBar-sh.xml).
 */
class RunnersBarRunAnythingProvider : RunAnythingProviderBase<String>() {

    private val prefix get() = RunnersBarSettings.getInstance().runAnythingPrefix + " "

    private fun command(value: String): String = value.removePrefix(prefix).trim()

    override fun findMatchingValue(dataContext: DataContext, pattern: String): String? =
        pattern.takeIf { it.startsWith(prefix) && command(it).isNotEmpty() }

    override fun getValues(dataContext: DataContext, pattern: String): Collection<String> =
        listOfNotNull(findMatchingValue(dataContext, pattern))

    override fun getCommand(value: String): String = value

    override fun getIcon(value: String): Icon = AllIcons.Actions.Execute

    override fun getCompletionGroupTitle(): String = RunnersBarBundle.message("runAnything.group")

    override fun getHelpCommand(): String = RunnersBarSettings.getInstance().runAnythingPrefix

    override fun getHelpCommandPlaceholder(): String = prefix + RunnersBarBundle.message("runAnything.placeholder")

    override fun getHelpDescription(): String = RunnersBarBundle.message("runAnything.description")

    override fun getHelpIcon(): Icon = AllIcons.Actions.Execute

    override fun execute(dataContext: DataContext, value: String) {
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return
        val command = command(value)
        val name = askConfigurationName(project, command, RunnersBarBundle.message("dialog.keep.title")) ?: return
        val delegate = RunnersBarDelegate.find(dataContext, command)
        if (delegate != null) {
            RunnersBarDelegate.keepNextStarted(project) { RunnersBarService.getInstance(project).keep(it, name) }
            delegate.second.execute(dataContext, delegate.first)
        } else {
            val settings = RunnersBarShell.create(project, command, workingDirectory(dataContext), name)
            RunnersBarService.getInstance(project).add(settings)
            RunnersBarExecution.execute(settings, debug = false)
        }
        forgetCommand(project)
    }

    /**
     * Der Befehl liegt jetzt als Konfiguration vor und gehört nicht in den Verlauf von Run Anything. Die IDE trägt ihn
     * erst nach [execute] ein, daher danach entfernen (dabei auch ältere Einträge mit dem Präfix).
     */
    private fun forgetCommand(project: Project) {
        val prefix = prefix
        ApplicationManager.getApplication().invokeLater({
            RunAnythingCache.getInstance(project).state.commands.removeIf { it.startsWith(prefix) }
        }, project.disposed)
    }

    /** Verzeichnis, das in Run Anything als Kontext gewählt ist (Projekt, Modul oder zuletzt benutztes Verzeichnis). */
    private fun workingDirectory(dataContext: DataContext): String? {
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return null
        return when (val context = RunAnythingProvider.EXECUTING_CONTEXT.getData(dataContext)) {
            is RunAnythingContext.ModuleContext -> context.module.guessModuleDir()?.path
            is RunAnythingContext.RecentDirectoryContext -> context.path
            else -> null
        } ?: project.guessProjectDir()?.path
    }
}
