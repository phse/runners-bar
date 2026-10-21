package app.heckit.ide.runnersbar

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
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
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Dimension
import javax.swing.JComponent

/** Woher Tasks kommen; Texte unter `<prefix>.*` im Bundle, gemeinsame unter `vscode.*`. */
enum class TaskSource(private val prefix: String, val notificationGroup: String) {
    VSCODE("vscode", "Runners Bar VS Code Tasks"),
    DDEV("ddev", "Runners Bar DDEV");

    fun find(basePath: String?): List<VsCodeTasks.Task> = when (this) {
        VSCODE -> VsCodeTasks.find(basePath)
        DDEV -> DdevCommands.find(basePath)
    }

    fun isPresent(basePath: String?): Boolean = when (this) {
        VSCODE -> VsCodeTasks.hasTasks(basePath)
        DDEV -> DdevCommands.hasProjects(basePath)
    }

    var offer: Boolean
        get() = when (this) {
            VSCODE -> RunnersBarSettings.getInstance().offerVsCodeTasks
            DDEV -> RunnersBarSettings.getInstance().offerDdevCommands
        }
        set(value) = when (this) {
            VSCODE -> RunnersBarSettings.getInstance().offerVsCodeTasks = value
            DDEV -> RunnersBarSettings.getInstance().offerDdevCommands = value
        }

    fun message(key: String, vararg params: Any): String = RunnersBarBundle.message("$prefix.$key", *params)
}

/**
 * Übernimmt Tasks aus .vscode/tasks.json (bzw. DDEV-Befehle, siehe [TaskSource]) als Shell-Konfigurationen, je Ordner in eine eigene Gruppe. Nur mit dem
 * Shell-Script-Plugin (registriert in runnersBar-sh.xml).
 */
object VsCodeTasksImport {

    /** Tasks, die übernommen werden können, aber noch keine Konfiguration haben (nie übernommen oder gelöscht). */
    fun pending(project: Project, tasks: List<VsCodeTasks.Task>): List<VsCodeTasks.Task> {
        val service = RunnersBarService.getInstance(project)
        return tasks.filter { it.unsupported != null || service.importedSettings(it.key) == null }
    }

    /** Auswahl anzeigen und übernehmen; schon übernommene Tasks werden dabei auf den Stand der tasks.json gebracht. */
    fun open(project: Project, source: TaskSource = TaskSource.VSCODE) {
        val tasks = source.find(project.basePath)
        if (tasks.isEmpty()) {
            Messages.showInfoMessage(project, source.message("none"), source.message("title"))
            return
        }
        val updated = updateImported(project, tasks)
        val pending = pending(project, tasks)
        if (pending.none { it.unsupported == null }) {
            Messages.showInfoMessage(project, source.message("allImported", tasks.size - pending.size, updated), source.message("title"))
            return
        }
        val dialog = VsCodeTasksDialog(project, source, pending, tasks.size - pending.size)
        if (!dialog.showAndGet()) return
        // Weggelassene Tasks gelten als entschieden: keine Meldung mehr, über das Menü bleiben sie importierbar.
        RunnersBarService.getInstance(project).markOffered(pending.map { it.key })
        import(project, dialog.selected, dialog::inTerminal)
    }

    private fun import(project: Project, selected: List<VsCodeTasks.Task>, inTerminal: (VsCodeTasks.Task) -> Boolean) {
        val service = RunnersBarService.getInstance(project)
        selected.forEach { task -> service.addToGroup(groupName(project, task), importOne(project, task, inTerminal(task))) }
        selected.firstOrNull()?.let { service.selectGroup(groupName(project, it)) }
    }

    /** Konfiguration für [task]; eine schon übernommene wird wiederverwendet. */
    fun importOne(project: Project, task: VsCodeTasks.Task, inTerminal: Boolean = task.needsTerminal): RunnerAndConfigurationSettings {
        val service = RunnersBarService.getInstance(project)
        service.importedSettings(task.key)?.let { return it }
        // Gleiche Labels in mehreren Ordnern (z. B. „ddev start“): die Gruppe anhängen.
        val group = groupName(project, task)
        val taken = RunManager.getInstance(project).allSettings.any { it.name == task.label }
        val settings = RunnersBarShell.createOrUpdate(project, task, if (taken) "${task.label} ($group)" else task.label, null, inTerminal)
        service.markImported(task.key, settings)
        return settings
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

    fun groupName(project: Project, task: VsCodeTasks.Task): String = task.group ?: task.folder.substringAfterLast('/').ifEmpty { project.name }
}

private class VsCodeTasksDialog(
    private val project: Project,
    private val source: TaskSource,
    private val tasks: List<VsCodeTasks.Task>,
    private val alreadyImported: Int,
) : DialogWrapper(project) {
    private val boxes = mutableMapOf<VsCodeTasks.Task, JBCheckBox>()

    val selected: List<VsCodeTasks.Task> get() = tasks.filter { boxes[it]?.isSelected == true }

    private val terminalBoxes = mutableMapOf<VsCodeTasks.Task, JBCheckBox>()

    /** „Terminal“ je Task; vorbelegt mit [VsCodeTasks.Task.needsTerminal]. */
    fun inTerminal(task: VsCodeTasks.Task): Boolean = terminalBoxes[task]?.isSelected ?: task.needsTerminal

    init {
        title = source.message("title")
        setOKButtonText(RunnersBarBundle.message("vscode.import"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label(source.message("intro")) }
        tasks.groupBy { it.folder }.forEach { (folder, folderTasks) ->
            val group = VsCodeTasksImport.groupName(project, folderTasks.first())
            group(RunnersBarBundle.message("vscode.folder", folder.ifEmpty { "." }, group)) {
                folderTasks.forEach { task ->
                    row {
                        val box = checkBox(task.label)
                        if (task.unsupported != null) {
                            box.enabled(false).comment(task.unsupported)
                        } else {
                            box.applyToComponent { isSelected = task.preselected }.comment((task.description ?: task.command).let { if (it.length > 90) it.take(87) + "..." else it })
                            boxes[task] = box.component
                            terminalBoxes[task] = checkBox(RunnersBarBundle.message("vscode.terminal"))
                                .applyToComponent { isSelected = task.needsTerminal }
                                .align(AlignX.RIGHT)
                                .component
                        }
                    }
                }
            }
        }
        row {
            link(RunnersBarBundle.message("vscode.selectAll")) { boxes.values.forEach { it.isSelected = true } }
            link(RunnersBarBundle.message("vscode.selectNone")) { boxes.values.forEach { it.isSelected = false } }
        }
        row { comment(RunnersBarBundle.message("vscode.terminal.comment")) }
        if (alreadyImported > 0) row { comment(source.message("alreadyImported", alreadyImported)) }
    }.let { content ->
        // Bei vielen Tasks (mehrere Unterprojekte mit je eigener tasks.json) scrollen statt über den Bildschirm hinaus wachsen.
        JBScrollPane(content).apply {
            border = null
            preferredSize = Dimension(minOf(content.preferredSize.width + 30, 760), minOf(content.preferredSize.height + 10, 560))
        }
    }
}

/** Meldet beim Öffnen eines Projekts neue Tasks in .vscode/tasks.json bzw. DDEV-Befehle, sofern in den Einstellungen gewünscht. */
class VsCodeTasksStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!RunnersBarShell.isAvailable) return
        TaskSource.entries.filter { it.offer }.forEach { offer(project, it) }
    }

    private suspend fun offer(project: Project, source: TaskSource) {
        val tasks = withContext(Dispatchers.IO) { source.find(project.basePath) }
        if (tasks.isEmpty()) return
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            val pending = VsCodeTasksImport.pending(project, tasks).filter { it.unsupported == null && it.preselected }
            // Erst nach einer Entscheidung im Dialog gemerkt: „Jetzt nicht“ oder Wegklicken fragt beim nächsten Öffnen wieder.
            if (RunnersBarService.getInstance(project).notOffered(pending.map { it.key }).isEmpty()) return@withContext
            NotificationGroupManager.getInstance().getNotificationGroup(source.notificationGroup)
                .createNotification(source.message("notification", pending.size), NotificationType.INFORMATION)
                .addAction(NotificationAction.createSimpleExpiring(RunnersBarBundle.message("vscode.notification.import")) {
                    VsCodeTasksImport.open(project, source)
                })
                .addAction(NotificationAction.createSimpleExpiring(RunnersBarBundle.message("vscode.notification.later")) {})
                .addAction(NotificationAction.createSimpleExpiring(RunnersBarBundle.message("vscode.notification.never")) {
                    source.offer = false
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

/** Find Action: „Import DDEV Commands...“ */
class ImportDdevCommandsAction : DumbAwareAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.let { DdevCommands.hasProjects(it.basePath) } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { VsCodeTasksImport.open(it, TaskSource.DDEV) }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
