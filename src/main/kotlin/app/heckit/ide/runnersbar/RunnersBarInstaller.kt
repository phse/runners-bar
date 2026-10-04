package app.heckit.ide.runnersbar

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.JComponent
import javax.swing.JPanel

class RunnersBarStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Der Frame samt Statuszeile ist beim Projektstart nicht immer sofort fertig.
        repeat(20) {
            val done = withContext(Dispatchers.EDT) { project.isDisposed || RunnersBarInstaller.install(project) }
            if (done) {
                startScreenshotHelper(project)
                return
            }
            delay(500)
        }
        logger<RunnersBarStartup>().warn("Runners Bar: status bar not found, bar not installed")
    }

    /** Nur in Builds mit -Pscreenshots enthalten (siehe build.gradle.kts), im Release fehlt die Klasse. */
    private fun startScreenshotHelper(project: Project) {
        val dir = System.getProperty("runnersbar.screenshots") ?: return
        runCatching {
            Class.forName("app.heckit.ide.runnersbar.screenshot.ScreenshotMaker")
                .getMethod("start", Project::class.java, String::class.java)
                .invoke(null, project, dir)
        }.onFailure { logger<RunnersBarStartup>().warn("Runners Bar: screenshot helper not available", it) }
    }
}

/**
 * Hängt die Runners Bar in den Frame, indem das Ziel in einen Wrapper gelegt wird (Leiste oben, Ziel darunter).
 * Ziel ist unten die Statuszeile, oben der mittlere Bereich mit Editor und Tool-Fenstern.
 */
object RunnersBarInstaller {
    private const val VISIBLE_KEY = "runnersbar.visible"
    private val GUARD_KEY = Key.create<HierarchyListener>("RunnersBar.guard")

    var isBarVisible: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(VISIBLE_KEY, true)
        set(value) {
            PropertiesComponent.getInstance().setValue(VISIBLE_KEY, value, true)
        }

    /** Vorgabe für alle Projekte; Projekte mit eigenem Wert behalten ihn. */
    fun setBarVisibleEverywhere(visible: Boolean) {
        isBarVisible = visible
        openProjects().forEach { applyVisibility(it) }
    }

    /** Nach Änderung der Vorgaben für Position oder Ausrichtung: in allen offenen Projekten neu einhängen. */
    fun reinstallAll() {
        openProjects().forEach { install(it) }
    }

    private fun openProjects() = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }

    private fun statusBarComponent(project: Project): JComponent? =
        WindowManager.getInstance().getStatusBar(project)?.component

    /** Inhalt des Frames (BorderLayout): Mitte Editor und Tool-Fenster, Süden die Statuszeile. */
    private fun frameContent(statusBar: JComponent): Container? {
        val parent = statusBar.parent ?: return null
        return if (parent is Host) parent.parent else parent
    }

    private fun unwrap(component: Component?): JComponent? = if (component is Host) component.target else component as? JComponent

    fun install(project: Project): Boolean {
        val statusBar = statusBarComponent(project) ?: return false
        val content = frameContent(statusBar) ?: return false
        val layout = content.layout as? BorderLayout ?: run {
            logger<RunnersBarInstaller>().warn("Runners Bar: unexpected status bar parent layout ${content.layout}")
            return true
        }
        val service = RunnersBarService.getInstance(project)
        val panel = service.panel
        panel.isVisible = service.isVisible
        panel.applySettings()

        val target = when (service.layout.position) {
            BarPosition.BOTTOM -> statusBar
            BarPosition.TOP -> unwrap(layout.getLayoutComponent(BorderLayout.CENTER)) ?: statusBar
        }
        if (target.parent is Host) return true

        unwrapAll(content)
        val constraints = layout.getConstraints(target) ?: BorderLayout.CENTER
        val index = content.components.indexOf(target)
        content.remove(target)
        content.add(Host(target, panel), constraints, index)
        content.revalidate()
        content.repaint()
        addGuard(project, target)
        return true
    }

    fun uninstall(project: Project) {
        val statusBar = statusBarComponent(project) ?: return
        val content = frameContent(statusBar) ?: return
        removeGuard(statusBar)
        unwrap((content.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.CENTER))?.let { removeGuard(it) }
        unwrapAll(content)
    }

    /** Ersetzt jeden Wrapper in [content] wieder durch das ursprüngliche Ziel. */
    private fun unwrapAll(content: Container) {
        for (host in content.components.filterIsInstance<Host>()) {
            val constraints = (content.layout as? BorderLayout)?.getConstraints(host)
            val index = content.components.indexOf(host)
            content.remove(host)
            host.remove(host.target)
            content.add(host.target, constraints, index)
        }
        content.revalidate()
        content.repaint()
    }

    /** Baut die IDE das Ziel neu ein (z. B. Präsentationsmodus), hängen wir uns erneut davor. */
    private fun addGuard(project: Project, target: JComponent) {
        if (target.getClientProperty(GUARD_KEY) != null) return
        val guard = HierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.PARENT_CHANGED.toLong() != 0L &&
                e.changed === target && target.parent != null && target.parent !is Host
            ) {
                ApplicationManager.getApplication().invokeLater({ install(project) }, project.disposed)
            }
        }
        target.putClientProperty(GUARD_KEY, guard)
        target.addHierarchyListener(guard)
    }

    private fun removeGuard(target: JComponent) {
        (target.getClientProperty(GUARD_KEY) as? HierarchyListener)?.let {
            target.removeHierarchyListener(it)
            target.putClientProperty(GUARD_KEY, null)
        }
    }

    fun applyVisibility(project: Project) {
        val service = RunnersBarService.getInstance(project)
        service.panel.isVisible = service.isVisible
    }

    private class Host(val target: JComponent, bar: JComponent) : JPanel(BorderLayout()) {
        init {
            isOpaque = false
            add(bar, BorderLayout.NORTH)
            add(target, BorderLayout.CENTER)
        }
    }
}
