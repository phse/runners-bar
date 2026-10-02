package app.heckit.ide.runnersbar

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.ProjectManager

/** View | Appearance | Runners Bar */
class ToggleRunnersBarAction : DumbAwareToggleAction() {
    override fun isSelected(e: AnActionEvent): Boolean = RunnersBarInstaller.isBarVisible

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        RunnersBarInstaller.isBarVisible = state
        ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed }
            .forEach { RunnersBarInstaller.applyVisibility(it) }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** View | Appearance | Runners Bar in This Project */
class ToggleRunnersBarInProjectAction : DumbAwareToggleAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        // Ist die Leiste global aus, hat der Projektschalter keine Wirkung.
        e.presentation.isEnabled = e.project != null && RunnersBarInstaller.isBarVisible
    }

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return !RunnersBarService.getInstance(project).isHiddenInProject
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        RunnersBarService.getInstance(project).isHiddenInProject = !state
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
