package app.heckit.ide.runnersbar

import com.intellij.execution.RunManager
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Dimension
import javax.swing.JComponent

/**
 * Übernimmt Tasks aus .vscode/tasks.json als Shell-Konfigurationen, je Ordner in eine eigene Gruppe. Nur mit dem
 * Shell-Script-Plugin (registriert in runnersBar-sh.xml).
 */
object VsCodeTasksImport {

    /** Tasks, die übernommen werden können, aber noch keine Konfiguration haben (nie übernommen oder gelöscht). */
    fun pending(project: Project, tasks: List<VsCodeTasks.Task>): List<VsCodeTasks.Task> {
        val service = RunnersBarService.getInstance(project)
        return tasks.filter { it.unsupported != null || service.importedSettings(it.key) == null }
    }

    /** Auswahl anzeigen und übernehmen; schon übernommene Tasks werden dabei auf den Stand der tasks.json gebracht. */
    fun open(project: Project) {
        val tasks = VsCodeTasks.find(project.basePath)
        if (tasks.isEmpty()) {
            Messages.showInfoMessage(project, RunnersBarBundle.message("vscode.none"), RunnersBarBundle.message("vscode.title"))
            return
        }
        val updated = updateImported(project, tasks)
        val pending = pending(project, tasks)
        if (pending.none { it.unsupported == null }) {
            Messages.showInfoMessage(project, RunnersBarBundle.message("vscode.allImported", tasks.size - pending.size, updated), RunnersBarBundle.message("vscode.title"))
            return
        }
        val dialog = VsCodeTasksDialog(project, pending, tasks.size - pending.size)
        if (!dialog.showAndGet()) return
        // Weggelassene Tasks gelten als entschieden: keine Meldung mehr, über das Menü bleiben sie importierbar.
        RunnersBarService.getInstance(project).markOffered(pending.map { it.key })
        import(project, dialog.selected)
    }

    private fun import(project: Project, selected: List<VsCodeTasks.Task>) {
        val service = RunnersBarService.getInstance(project)
        val runManager = RunManager.getInstance(project)
        selected.forEach { task ->
            val group = groupName(project, task)
            // Gleiche Labels in mehreren Ordnern (z. B. „DDEV start“): den Ordner anhängen.
            val name = if (runManager.allSettings.any { it.name == task.label }) "${task.label} ($group)" else task.label
            val settings = RunnersBarShell.createOrUpdate(project, task, name, null)
            service.markImported(task.key, settings)
            service.addToGroup(group, settings)
        }
        selected.firstOrNull()?.let { service.selectGroup(groupName(project, it)) }
    }

    /** Befehl, Arbeitsverzeichnis und Umgebung übernommener Tasks nachziehen; liefert die Zahl der geänderten. */
    private fun updateImported(project: Project, tasks: List<VsCodeTasks.Task>): Int {
        val service = RunnersBarService.getInstance(project)
        return tasks.filter { it.unsupported == null }.count { task ->
            val settings = service.importedSettings(task.key) ?: return@count false
            if (!RunnersBarShell.differs(settings, task)) return@count false
            RunnersBarShell.createOrUpdate(project, task, settings.name, settings)
            true
        }
    }

    fun groupName(project: Project, task: VsCodeTasks.Task): String = task.folder.substringAfterLast('/').ifEmpty { project.name }
}

private class VsCodeTasksDialog(
    private val project: Project,
    private val tasks: List<VsCodeTasks.Task>,
    private val alreadyImported: Int,
) : DialogWrapper(project) {
    private val boxes = mutableMapOf<VsCodeTasks.Task, JBCheckBox>()

    val selected: List<VsCodeTasks.Task> get() = tasks.filter { boxes[it]?.isSelected == true }

    init {
        title = RunnersBarBundle.message("vscode.title")
        setOKButtonText(RunnersBarBundle.message("vscode.import"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label(RunnersBarBundle.message("vscode.intro")) }
        tasks.groupBy { it.folder }.forEach { (folder, folderTasks) ->
            val group = VsCodeTasksImport.groupName(project, folderTasks.first())
            group(RunnersBarBundle.message("vscode.folder", folder.ifEmpty { "." }, group)) {
                folderTasks.forEach { task ->
                    row {
                        val box = checkBox(task.label)
                        if (task.unsupported != null) {
                            box.enabled(false).comment(task.unsupported)
                        } else {
                            box.applyToComponent { isSelected = true }.comment(task.command.let { if (it.length > 90) it.take(87) + "..." else it })
                            boxes[task] = box.component
                        }
                    }
                }
            }
        }
        row {
            link(RunnersBarBundle.message("vscode.selectAll")) { boxes.values.forEach { it.isSelected = true } }
            link(RunnersBarBundle.message("vscode.selectNone")) { boxes.values.forEach { it.isSelected = false } }
        }
        if (alreadyImported > 0) row { comment(RunnersBarBundle.message("vscode.alreadyImported", alreadyImported)) }
    }.let { content ->
        // Bei vielen Tasks (mehrere Unterprojekte mit je eigener tasks.json) scrollen statt über den Bildschirm hinaus wachsen.
        JBScrollPane(content).apply {
            border = null
            preferredSize = Dimension(minOf(content.preferredSize.width + 30, 760), minOf(content.preferredSize.height + 10, 560))
        }
    }
}

/** Meldet beim Öffnen eines Projekts neue Tasks in .vscode/tasks.json, sofern in den Einstellungen gewünscht. */
class VsCodeTasksStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!RunnersBarSettings.getInstance().offerVsCodeTasks || !RunnersBarShell.isAvailable) return
        val tasks = withContext(Dispatchers.IO) { VsCodeTasks.find(project.basePath) }
        if (tasks.isEmpty()) return
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            val pending = VsCodeTasksImport.pending(project, tasks).filter { it.unsupported == null }
            // Erst nach einer Entscheidung im Dialog gemerkt: „Jetzt nicht“ oder Wegklicken fragt beim nächsten Öffnen wieder.
            if (RunnersBarService.getInstance(project).notOffered(pending.map { it.key }).isEmpty()) return@withContext
            NotificationGroupManager.getInstance().getNotificationGroup("Runners Bar VS Code Tasks")
                .createNotification(RunnersBarBundle.message("vscode.notification", pending.size), NotificationType.INFORMATION)
                .addAction(NotificationAction.createSimpleExpiring(RunnersBarBundle.message("vscode.notification.import")) {
                    VsCodeTasksImport.open(project)
                })
                .addAction(NotificationAction.createSimpleExpiring(RunnersBarBundle.message("vscode.notification.later")) {})
                .addAction(NotificationAction.createSimpleExpiring(RunnersBarBundle.message("vscode.notification.never")) {
                    RunnersBarSettings.getInstance().offerVsCodeTasks = false
                })
                .notify(project)
        }
    }
}

/** Find Action: „Import VS Code Tasks...“ */
class ImportVsCodeTasksAction : DumbAwareAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.let { VsCodeTasks.hasTasks(it.basePath) } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(VsCodeTasksImport::open)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
