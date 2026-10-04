package app.heckit.ide.runnersbar

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareToggleAction

/** View | Appearance | Runners Bar */
class ToggleRunnersBarAction : DumbAwareToggleAction() {
    override fun isSelected(e: AnActionEvent): Boolean = RunnersBarInstaller.isBarVisible

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        RunnersBarInstaller.setBarVisibleEverywhere(state)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** View | Appearance | Runners Bar in This Project */
class ToggleRunnersBarInProjectAction : DumbAwareToggleAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = e.project != null
    }

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return RunnersBarService.getInstance(project).isVisible
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        RunnersBarService.getInstance(project).setVisibleInProject(state)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
