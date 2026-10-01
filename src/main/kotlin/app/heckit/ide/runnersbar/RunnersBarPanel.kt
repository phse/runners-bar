package app.heckit.ide.runnersbar

import com.intellij.execution.RunManager
import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/** Die eigentliche Leiste: links „+“ und Gruppen-Umschalter, rechts davon die Tabs. */
class RunnersBarPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {

    private val service get() = RunnersBarService.getInstance(project)
    private val tabs = TabsPanel()
    internal val tabComponents: List<RunnersBarTab> get() = tabs.components.filterIsInstance<RunnersBarTab>()
    private val tracker = RunningTracker(project, parent) { refreshTabs() }

    internal val addButton = HoverLabel(
        icon = AllIcons.General.Add,
        tooltip = RunnersBarBundle.message("bar.add.tooltip"),
    ).also { it.onClick = { showAddPopup(it) } }

    internal val groupSwitcher = HoverLabel(
        icon = AllIcons.General.ChevronDown,
        tooltip = RunnersBarBundle.message("group.switch.tooltip"),
    ).also {
        it.horizontalTextPosition = SwingConstants.LEADING
        it.onClick = { showGroupPopup(it) }
        it.onPopup = it.onClick
    }

    init {
        isOpaque = true
        background = JBColor.namedColor("StatusBar.background", UIUtil.getPanelBackground())
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineTop(JBColor.border()),
            JBUI.Borders.empty(0, 4),
        )

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), JBUI.scale(2))).apply {
            isOpaque = false
            add(addButton)
            add(groupSwitcher)
            add(VerticalLine())
        }
        add(left, BorderLayout.WEST)
        add(tabs, BorderLayout.CENTER)

        val connection = project.messageBus.connect(parent)
        connection.subscribe(RunnersBarListener.TOPIC, object : RunnersBarListener {
            override fun entriesChanged() = rebuild()
            override fun entryUpdated(entry: RunnersBarEntry) = refreshTabs()
        })
        connection.subscribe(RunManagerListener.TOPIC, object : RunManagerListener {
            override fun runConfigurationAdded(settings: RunnerAndConfigurationSettings) = refreshTabs()
            override fun runConfigurationRemoved(settings: RunnerAndConfigurationSettings) = refreshTabs()
            override fun runConfigurationChanged(settings: RunnerAndConfigurationSettings, existingId: String?) {
                service.configurationChanged(settings, existingId)
                refreshTabs()
            }

            override fun stateLoaded(runManager: RunManager, isFirstLoadState: Boolean) = refreshTabs()
        })
        rebuild()
    }

    private fun rebuild() {
        if (project.isDisposed) return
        val entries = service.entries
        val groups = service.groupNames

        addButton.text = if (entries.isEmpty()) RunnersBarBundle.message("bar.add.empty") else null
        groupSwitcher.isVisible = groups.size > 1
        groupSwitcher.text = groupDisplayName(service.activeGroupName)

        tabs.removeAll()
        entries.forEach { tabs.add(RunnersBarTab(project, it, tracker, this)) }
        revalidate()
        repaint()
    }

    private fun refreshTabs() {
        tabs.components.filterIsInstance<RunnersBarTab>().forEach { it.refresh() }
    }

    // ---- Popups ----

    internal fun showAddPopup(anchor: JComponent): JBPopup {
        val candidates = RunManager.getInstance(project).allSettings.filter { !service.contains(it) }
        val group = DefaultActionGroup()
        if (candidates.isEmpty()) {
            group.add(popupAction(RunnersBarBundle.message("bar.add.nothing"), null, enabled = false) {})
        }
        candidates.forEach { settings ->
            group.add(popupAction(settings.name, settings.configuration.icon) { service.add(settings) })
        }
        group.add(Separator.getInstance())
        group.add(popupAction(RunnersBarBundle.message("group.new"), AllIcons.Actions.NewFolder) { newGroup() })
        return showActionPopup(RunnersBarBundle.message("bar.add.title"), group, anchor)
    }

    internal fun showGroupPopup(anchor: JComponent): JBPopup {
        val active = service.activeGroupName
        val group = DefaultActionGroup()
        service.groupNames.forEach { name ->
            val icon = if (name == active) AllIcons.Actions.Checked else EmptyIcon.ICON_16
            group.add(popupAction(groupDisplayName(name), icon) { service.selectGroup(name) })
        }
        group.add(Separator.getInstance())
        group.add(popupAction(RunnersBarBundle.message("group.new"), AllIcons.Actions.NewFolder) { newGroup() })
        group.add(popupAction(RunnersBarBundle.message("group.rename", groupDisplayName(active)), AllIcons.Actions.Edit) {
            renameGroup(active)
        })
        group.add(popupAction(RunnersBarBundle.message("group.remove", groupDisplayName(active)), AllIcons.General.Remove) {
            removeGroup(active)
        })
        return showActionPopup(null, group, anchor)
    }

    private fun newGroup() {
        val name = askGroupName(RunnersBarBundle.message("group.new.title"), "") ?: return
        service.addGroup(name)
    }

    private fun renameGroup(name: String) {
        val newName = askGroupName(RunnersBarBundle.message("group.rename.title"), groupDisplayName(name), name) ?: return
        service.renameGroup(name, newName)
    }

    private fun removeGroup(name: String) {
        val answer = Messages.showYesNoDialog(
            project,
            RunnersBarBundle.message("group.remove.confirm", groupDisplayName(name)),
            RunnersBarBundle.message("group.remove.title"),
            Messages.getQuestionIcon(),
        )
        if (answer == Messages.YES) service.removeGroup(name)
    }

    private fun askGroupName(title: String, initial: String, currentName: String? = null): String? {
        val validator = object : InputValidatorEx {
            override fun getErrorText(inputString: String): String? {
                val name = inputString.trim()
                return when {
                    name.isEmpty() -> RunnersBarBundle.message("group.name.empty")
                    name != currentName && service.hasGroup(name) -> RunnersBarBundle.message("group.name.exists")
                    else -> null
                }
            }
        }
        return Messages.showInputDialog(
            project,
            RunnersBarBundle.message("group.name.label"),
            title,
            null,
            initial,
            validator,
        )?.trim()?.takeIf { it != currentName }
    }

    // ---- Drag & Drop ----

    /** Aktualisiert die Einfügemarke bzw. den Entfernen-Zustand, während ein Tab gezogen wird. */
    fun dragMoved(tab: RunnersBarTab, e: MouseEvent) {
        val outside = isOutside(e)
        tab.dragState = if (outside) RunnersBarTab.DragState.REMOVE else RunnersBarTab.DragState.MOVE
        tabs.dropSlot = if (outside) -1 else tabs.slotAt(SwingUtilities.convertPoint(e.component, e.point, tabs))
        tabs.repaint()
    }

    fun dragEnded(tab: RunnersBarTab, e: MouseEvent) {
        val outside = isOutside(e)
        val slot = tabs.slotAt(SwingUtilities.convertPoint(e.component, e.point, tabs))
        tab.dragState = RunnersBarTab.DragState.NONE
        tabs.dropSlot = -1
        tabs.repaint()
        if (outside) service.remove(tab.entry) else service.moveTo(tab.entry, slot)
    }

    private fun isOutside(e: MouseEvent): Boolean {
        val p = SwingUtilities.convertPoint(e.component, e.point, this)
        val margin = JBUI.scale(12)
        return !Rectangle(-margin, -margin, width + 2 * margin, height + 2 * margin).contains(p)
    }

    private inner class TabsPanel : JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(2), JBUI.scale(2))) {
        /** Einfügeposition, an der die Marke gezeichnet wird; -1 = keine. */
        var dropSlot = -1

        init {
            isOpaque = false
        }

        private val tabList get() = components.filterIsInstance<RunnersBarTab>()

        /** Nächstgelegene Einfügeposition zu [p], auch über mehrere Zeilen hinweg. */
        fun slotAt(p: Point): Int {
            val list = tabList
            if (list.isEmpty()) return 0
            return (0..list.size).minBy { slot ->
                val anchor = slotAnchor(list, slot)
                val dx = (p.x - anchor.x).toDouble()
                val dy = (p.y - anchor.y).toDouble()
                dx * dx + 9 * dy * dy
            }
        }

        private fun slotAnchor(list: List<RunnersBarTab>, slot: Int): Point {
            val ref = list[slot.coerceAtMost(list.size - 1)]
            val x = if (slot < list.size) ref.x else ref.x + ref.width
            return Point(x, ref.y + ref.height / 2)
        }

        override fun paintChildren(g: Graphics) {
            super.paintChildren(g)
            val list = tabList
            if (dropSlot < 0 || list.isEmpty()) return
            val ref = list[dropSlot.coerceAtMost(list.size - 1)]
            val gap = JBUI.scale(2)
            val x = if (dropSlot < list.size) ref.x - gap / 2 - 1 else ref.x + ref.width + gap / 2 - 1
            g.color = JBUI.CurrentTheme.Focus.focusColor()
            g.fillRect(x, ref.y, JBUI.scale(2), ref.height)
        }
    }

    private class VerticalLine : JComponent() {
        override fun getPreferredSize() = Dimension(JBUI.scale(9), JBUI.scale(20))

        override fun paintComponent(g: Graphics) {
            g.color = JBColor.border()
            val x = width / 2
            g.drawLine(x, JBUI.scale(3), x, height - JBUI.scale(3))
        }
    }

    private fun groupDisplayName(name: String): String =
        if (name == RunnersBarGroup.DEFAULT_GROUP) RunnersBarBundle.message("group.default") else name

    private fun showActionPopup(title: String?, group: ActionGroup, anchor: JComponent): JBPopup =
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                title,
                group,
                SimpleDataContext.getProjectContext(project),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true,
            )
            .also { it.showAbove(anchor) }
}

fun popupAction(text: String, icon: Icon?, enabled: Boolean = true, block: () -> Unit): AnAction =
    object : DumbAwareAction(text, null, icon) {
        override fun actionPerformed(e: AnActionEvent) = block()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

/** Zeigt ein Popup oberhalb der Komponente an (die Leiste sitzt ja am unteren Fensterrand). */
fun JBPopup.showAbove(anchor: JComponent) {
    val height = content.preferredSize.height
    show(RelativePoint(anchor, Point(0, -height)))
}
