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
import com.intellij.ui.dsl.builder.columns
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
    DDEV("ddev", "Runners Bar DDEV"),
    SCRIPTS("scripts", "Runners Bar Scripts");

    fun find(basePath: String?): List<VsCodeTasks.Task> = when (this) {
        VSCODE -> VsCodeTasks.find(basePath)
        DDEV -> DdevCommands.find(basePath)
        SCRIPTS -> ProjectScripts.find(basePath)
    }

    fun isPresent(basePath: String?): Boolean = when (this) {
        VSCODE -> VsCodeTasks.hasTasks(basePath)
        DDEV -> DdevCommands.hasProjects(basePath)
        SCRIPTS -> ProjectScripts.hasScripts(basePath)
    }

    var offer: Boolean
        get() = when (this) {
            VSCODE -> RunnersBarSettings.getInstance().offerVsCodeTasks
            DDEV -> RunnersBarSettings.getInstance().offerDdevCommands
            SCRIPTS -> RunnersBarSettings.getInstance().offerProjectScripts
        }
        set(value) = when (this) {
            VSCODE -> RunnersBarSettings.getInstance().offerVsCodeTasks = value
            DDEV -> RunnersBarSettings.getInstance().offerDdevCommands = value
            SCRIPTS -> RunnersBarSettings.getInstance().offerProjectScripts = value
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
    fun open(project: Project, source: TaskSource = TaskSource.VSCODE, only: List<VsCodeTasks.Task>? = null) {
        val tasks = only ?: source.find(project.basePath)
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
        import(project, dialog.selected, dialog::inTerminal, dialog::groupFor)
    }

    private fun import(
        project: Project,
        selected: List<VsCodeTasks.Task>,
        inTerminal: (VsCodeTasks.Task) -> Boolean,
        groupFor: (VsCodeTasks.Task) -> String,
    ) {
        val service = RunnersBarService.getInstance(project)
        selected.forEach { task -> service.addToGroup(groupFor(task), importOne(project, task, inTerminal(task))) }
        selected.firstOrNull()?.let { service.selectGroup(groupFor(it)) }
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

    private val groupCombos = mutableMapOf<VsCodeTasks.Task, javax.swing.JComboBox<String>>()

    /** Gewählte Zielgruppe; leer = Vorschlag. */
    fun groupFor(task: VsCodeTasks.Task): String =
        (groupCombos[task]?.editor?.item as? String ?: groupCombos[task]?.selectedItem as? String)?.trim()?.ifEmpty { null }
            ?: VsCodeTasksImport.groupName(project, task)

    /** „Terminal“ je Task; vorbelegt mit [VsCodeTasks.Task.needsTerminal]. */
    fun inTerminal(task: VsCodeTasks.Task): Boolean = terminalBoxes[task]?.isSelected ?: task.needsTerminal

    init {
        title = source.message("title")
        setOKButtonText(RunnersBarBundle.message("vscode.import"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { text(source.message("intro"), maxLineLength = TEXT_WIDTH) }
        // Je Zielgruppe ein Abschnitt; bei Projekt-Scripts mit der Quelldatei (package.json, Makefile …) als Überschrift.
        tasks.groupBy { it.folder to VsCodeTasksImport.groupName(project, it) }.forEach { (key, folderTasks) ->
            val (folder, group) = key
            val origin = folderTasks.first().sourceFile?.let { if (folder.isEmpty()) it else "$folder/$it" } ?: folder.ifEmpty { "." }
            group(origin) {
                // Zielgruppe wie bei Changelists: vorgeschlagen, aber eine bestehende wählbar oder ein eigener Name.
                row(RunnersBarBundle.message("vscode.targetGroup")) {
                    val names = (listOf(group) + RunnersBarService.getInstance(project).groupNames).distinct()
                    val combo = comboBox(names).columns(com.intellij.ui.dsl.builder.COLUMNS_MEDIUM)
                        .applyToComponent { isEditable = true; selectedItem = group }.component
                    folderTasks.forEach { groupCombos[it] = combo }
                }
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
        row { comment(RunnersBarBundle.message("vscode.terminal.comment"), maxLineLength = TEXT_WIDTH) }
        if (alreadyImported > 0) row { comment(source.message("alreadyImported", alreadyImported)) }
    }.let { content ->
        // Bei vielen Tasks (mehrere Unterprojekte mit je eigener tasks.json) scrollen statt über den Bildschirm hinaus wachsen.
        JBScrollPane(content).apply {
            border = null
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            // Platz für den senkrechten Scrollbalken, sonst liegt er über den „Terminal“-Häkchen.
            val scrollBar = verticalScrollBar.preferredSize.width
            preferredSize = Dimension(minOf(content.preferredSize.width + scrollBar + 20, 820), minOf(content.preferredSize.height + 10, 560))
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

/** Find Action: „Import Project Scripts...“ (package.json, composer.json, Makefile, justfile) */
class ImportProjectScriptsAction : DumbAwareAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.let { ProjectScripts.hasScripts(it.basePath) } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { VsCodeTasksImport.open(it, TaskSource.SCRIPTS) }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** Umbruchbreite (Zeichen) der langen Texte im Import-Dialog, damit sie ihn nicht verbreitern. */
private const val TEXT_WIDTH = 90

/**
 * Rechtsklick auf package.json, composer.json, Makefile oder justfile (Projektansicht, Editor, Editor-Reiter):
 * „Scripts in die Runners Bar importieren...“ - derselbe Dialog, nur mit den Scripts dieser Datei.
 */
class ImportScriptsFromFileAction : DumbAwareAction() {
    override fun update(e: AnActionEvent) {
        val file = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && !file.isDirectory && ProjectScripts.isScriptFile(file.name)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE) ?: return
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
            ?.let(com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()::saveDocument)
        val tasks = ProjectScripts.findIn(project.basePath, file.toNioPath())
        VsCodeTasksImport.open(project, TaskSource.SCRIPTS, tasks)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
