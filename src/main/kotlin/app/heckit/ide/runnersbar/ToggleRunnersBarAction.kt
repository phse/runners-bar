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
