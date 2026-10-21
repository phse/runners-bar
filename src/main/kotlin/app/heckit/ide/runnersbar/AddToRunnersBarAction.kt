package app.heckit.ide.runnersbar

import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/**
 * „Zur Runners Bar hinzufügen…“ im Kontextmenü (Editor, Projektansicht), z. B. auf einem Skript in der package.json.
 * Nimmt die passende Konfiguration, legt sie bei Bedarf an und fragt vorher den Namen ab.
 */
class AddToRunnersBarAction : AnAction(), DumbAware {
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && addable(project, e.dataContext, e.place) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        addable(project, e.dataContext, e.place)?.let { addWithName(project, it) }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {
        /** Vorhandene Konfiguration für die Stelle, sonst eine neue (noch nicht im RunManager); null, wenn schon in der Leiste. */
        fun addable(project: Project, dataContext: DataContext, place: String): RunnerAndConfigurationSettings? {
            val context = ConfigurationContext.getFromContext(dataContext, place)
            val settings = context.findExisting() ?: context.configuration ?: return null
            return settings.takeUnless { RunnersBarService.getInstance(project).contains(it) }
        }

        fun addWithName(project: Project, settings: RunnerAndConfigurationSettings) {
            val name = askConfigurationName(project, settings.name, RunnersBarBundle.message("dialog.addToBar.title")) ?: return
            RunnersBarService.getInstance(project).keep(settings, name)
        }
    }
}

/**
 * Derselbe Eintrag im Menü am Run-Icon. Das Menü übernimmt nur Gruppen und reicht die Zeile des Icons nur an
 * [getChildren] weiter (die Kinder selbst sähen die Cursorzeile), deshalb wird die Konfiguration hier bestimmt.
 */
class AddToRunnersBarGutterGroup : ActionGroup(), DumbAware {
    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return EMPTY_ARRAY
        val settings = AddToRunnersBarAction.addable(project, e.dataContext, e.place) ?: return EMPTY_ARRAY
        val template = ActionManager.getInstance().getAction("RunnersBar.AddFromContext").templatePresentation
        return arrayOf(object : AnAction(template.text, template.description, template.icon), DumbAware {
            override fun actionPerformed(e: AnActionEvent) = AddToRunnersBarAction.addWithName(project, settings)
        })
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
