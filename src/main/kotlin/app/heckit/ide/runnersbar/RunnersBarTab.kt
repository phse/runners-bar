package app.heckit.ide.runnersbar

import com.intellij.execution.impl.RunDialog
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Ein Eintrag der Leiste: Klick auf den Namen startet, der Pfeil öffnet das Menü, Ziehen verschiebt. */
class RunnersBarTab(
    private val project: Project,
    val entry: RunnersBarEntry,
    private val tracker: RunningTracker,
    private val bar: RunnersBarPanel,
    /** Schwebende Kopie, die beim Ziehen der Maus folgt (siehe [RunnersBarPanel.dragMoved]). */
    private val ghost: Boolean = false,
) : JPanel(BorderLayout()) {

    /** Beim Ziehen ist der Tab in der Leiste nur ein leerer Platzhalter, die [ghost]-Kopie zeigt den Zustand. */
    enum class DragState { NONE, MOVE, REMOVE }

    var dragState = DragState.NONE
        set(value) {
            if (field == value) return
            field = value
            refresh()
            revalidate()
        }

    private val service get() = RunnersBarService.getInstance(project)
    private val main = HoverLabel()
    private val arrow = HoverLabel(icon = AllIcons.General.ChevronDown, tooltip = RunnersBarBundle.message("tab.menu.tooltip"))

    init {
        isOpaque = false
        background = JBColor.namedColor("StatusBar.background", UIUtil.getPanelBackground())
        border = JBUI.Borders.empty(1)
        add(main, BorderLayout.CENTER)
        add(arrow, BorderLayout.EAST)
        main.onClick = { run(entry.debug) }
        main.onPopup = { showMenu() }
        arrow.onClick = { showMenu() }
        arrow.onPopup = { showMenu() }
        val drag = object : HoverLabel.DragListener {
            override fun dragMoved(e: MouseEvent) = bar.dragMoved(this@RunnersBarTab, e)
            override fun dragEnded(e: MouseEvent) = bar.dragEnded(this@RunnersBarTab, e)
        }
        main.dragListener = drag
        arrow.dragListener = drag
        refresh()
    }

    fun refresh() {
        val settings = service.findSettings(entry)
        val name = settings?.name ?: entry.name
        val running = tracker.isRunning(entry.configId)
        val baseIcon: Icon = when {
            dragState == DragState.REMOVE -> AllIcons.Actions.GC
            settings == null -> AllIcons.General.Warning
            entry.debug -> AllIcons.Actions.StartDebugger
            else -> AllIcons.Actions.Execute
        }
        main.icon = if (running && dragState != DragState.REMOVE) ExecutionUtil.getLiveIndicator(baseIcon) else baseIcon
        main.text = name
        main.foreground = if (settings == null) UIUtil.getContextHelpForeground() else UIUtil.getLabelForeground()
        main.toolTipText = if (ghost) null else when {
            settings == null -> RunnersBarBundle.message("tab.tooltip.missing", name)
            entry.debug -> RunnersBarBundle.message("tab.tooltip.debug", name)
            else -> RunnersBarBundle.message("tab.tooltip.run", name)
        } + if (running) " " + RunnersBarBundle.message("tab.tooltip.running") else ""
        repaint()
    }

    private fun run(debug: Boolean) {
        val settings = service.findSettings(entry) ?: return
        RunnersBarExecution.execute(settings, debug)
    }

    private fun canDebug(): Boolean = service.findSettings(entry)?.let { RunnersBarExecution.canDebug(it) } == true

    internal fun showMenu(): JBPopup {
        val group = DefaultActionGroup()

        // Text und Icon hängen am aktuellen Modus, damit der Eintrag nach dem Umschalten stimmt.
        group.add(object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = run(!entry.debug)
            override fun update(e: AnActionEvent) {
                val p = e.presentation
                if (entry.debug) {
                    p.text = RunnersBarBundle.message("menu.runOnce")
                    p.icon = AllIcons.Actions.Execute
                    p.isEnabled = service.findSettings(entry) != null
                } else {
                    p.text = RunnersBarBundle.message("menu.debugOnce")
                    p.icon = AllIcons.Actions.StartDebugger
                    p.isEnabled = canDebug()
                }
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        group.add(object : DumbAwareToggleAction(RunnersBarBundle.message("menu.debugMode")) {
            override fun isSelected(e: AnActionEvent) = entry.debug
            override fun setSelected(e: AnActionEvent, state: Boolean) = service.setDebug(entry, state)
            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.isEnabled = entry.debug || canDebug()
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        if (tracker.isRunning(entry.configId)) {
            group.add(popupAction(RunnersBarBundle.message("menu.stop"), AllIcons.Actions.Suspend) { tracker.stop(entry.configId) })
        }
        group.add(Separator.getInstance())

        val settings = service.findSettings(entry)
        group.add(popupAction(RunnersBarBundle.message("menu.settings"), AllIcons.General.Settings, settings != null) {
            settings?.let { RunDialog.editConfiguration(project, it, RunnersBarBundle.message("dialog.edit.title")) }
        })
        val entries = service.entries
        val index = entries.indexOf(entry)
        group.add(popupAction(RunnersBarBundle.message("menu.moveLeft"), AllIcons.Actions.Back, index > 0) {
            service.move(entry, -1)
        })
        group.add(popupAction(RunnersBarBundle.message("menu.moveRight"), AllIcons.Actions.Forward, index in 0 until entries.size - 1) {
            service.move(entry, 1)
        })
        group.add(Separator.getInstance())
        group.add(popupAction(RunnersBarBundle.message("menu.remove"), AllIcons.General.Remove) { service.remove(entry) })

        return JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null,
                group,
                SimpleDataContext.getProjectContext(project),
                JBPopupFactory.ActionSelectionAid.MNEMONICS,
                true,
            )
            .also { it.showAtBar(this) }
    }

    /** Platzhalter: hält beim Verschieben die Lücke frei, beim Rausziehen schrumpft er auf null. */
    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        return if (!ghost && dragState == DragState.REMOVE) Dimension(0, size.height) else size
    }

    override fun paint(g: Graphics) {
        if (!ghost && dragState != DragState.NONE) return
        super.paint(g)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(8)
            val fill = when {
                dragState == DragState.REMOVE -> REMOVE_BACKGROUND
                tracker.isRunning(entry.configId) -> RUNNING_BACKGROUND
                ghost -> background
                else -> null
            }
            if (fill != null) {
                g2.color = fill
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            }
            g2.color = if (dragState == DragState.REMOVE) JBColor.RED else JBColor.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        val RUNNING_BACKGROUND = JBColor(0xE6F4EA, 0x2B3A2F)
        val REMOVE_BACKGROUND = JBColor(0xFADBD8, 0x5E2D2D)
    }
}

/** Label mit Hover-Hintergrund, das Klicks, Kontextmenü-Gesten und Ziehen meldet. */
class HoverLabel(icon: Icon? = null, text: String? = null, tooltip: String? = null) : JBLabel(text ?: "", icon, LEFT) {

    interface DragListener {
        fun dragMoved(e: MouseEvent)
        fun dragEnded(e: MouseEvent)
    }

    var onClick: (() -> Unit)? = null
    var onPopup: (() -> Unit)? = null
    var dragListener: DragListener? = null

    private var hovered = false
    private var pressed = false
    private var pressPoint: Point? = null
    private var dragging = false

    init {
        toolTipText = tooltip
        border = JBUI.Borders.empty(2, 6)
        iconTextGap = JBUI.scale(4)
        val mouse = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                repaint()
            }

            override fun mousePressed(e: MouseEvent) {
                if (handlePopup(e)) return
                if (SwingUtilities.isLeftMouseButton(e)) {
                    pressed = true
                    pressPoint = e.point
                    repaint()
                }
            }

            override fun mouseDragged(e: MouseEvent) {
                val listener = dragListener ?: return
                val start = pressPoint ?: return
                if (!dragging && start.distance(e.point) < JBUI.scale(DRAG_THRESHOLD)) return
                dragging = true
                listener.dragMoved(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                val wasPressed = pressed
                val wasDragging = dragging
                pressed = false
                pressPoint = null
                dragging = false
                repaint()
                if (wasDragging) {
                    dragListener?.dragEnded(e)
                    return
                }
                if (handlePopup(e)) return
                if (wasPressed && SwingUtilities.isLeftMouseButton(e) && contains(e.point)) onClick?.invoke()
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    private fun handlePopup(e: MouseEvent): Boolean {
        if (!e.isPopupTrigger) return false
        (onPopup ?: onClick)?.invoke()
        return true
    }

    override fun paintComponent(g: Graphics) {
        if ((hovered || pressed) && !dragging) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (pressed) {
                    JBUI.CurrentTheme.ActionButton.pressedBackground()
                } else {
                    JBUI.CurrentTheme.ActionButton.hoverBackground()
                }
                val arc = JBUI.scale(6)
                g2.fillRoundRect(0, 0, width, height, arc, arc)
            } finally {
                g2.dispose()
            }
        }
        super.paintComponent(g)
    }

    private companion object {
        const val DRAG_THRESHOLD = 5
    }
}
