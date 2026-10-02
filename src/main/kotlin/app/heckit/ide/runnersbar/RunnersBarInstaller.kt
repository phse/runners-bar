package app.heckit.ide.runnersbar

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
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

/** Hängt die Runners Bar direkt über die Statuszeile, indem die Statuszeile in einen Wrapper gelegt wird. */
object RunnersBarInstaller {
    private const val VISIBLE_KEY = "runnersbar.visible"
    private val GUARD_KEY = Key.create<HierarchyListener>("RunnersBar.guard")

    var isBarVisible: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(VISIBLE_KEY, true)
        set(value) {
            PropertiesComponent.getInstance().setValue(VISIBLE_KEY, value, true)
        }

    private fun statusBarComponent(project: Project): JComponent? =
        WindowManager.getInstance().getStatusBar(project)?.component

    fun install(project: Project): Boolean {
        val statusBar = statusBarComponent(project) ?: return false
        val parent = statusBar.parent ?: return false
        val panel = RunnersBarService.getInstance(project).panel
        panel.isVisible = isBarVisible(project)

        if (parent is Host) return true
        val layout = parent.layout as? BorderLayout ?: run {
            logger<RunnersBarInstaller>().warn("Runners Bar: unexpected status bar parent layout ${parent.layout}")
            return true
        }

        val constraints = layout.getConstraints(statusBar) ?: BorderLayout.SOUTH
        val index = parent.components.indexOf(statusBar)
        parent.remove(statusBar)
        parent.add(Host(statusBar, panel), constraints, index)
        parent.revalidate()
        parent.repaint()

        // Baut die IDE die Statuszeile neu ein (z. B. Präsentationsmodus), hängen wir uns erneut davor.
        if (statusBar.getClientProperty(GUARD_KEY) == null) {
            val guard = HierarchyListener { e ->
                if (e.changeFlags and HierarchyEvent.PARENT_CHANGED.toLong() != 0L &&
                    e.changed === statusBar && statusBar.parent != null && statusBar.parent !is Host
                ) {
                    ApplicationManager.getApplication().invokeLater({ install(project) }, project.disposed)
                }
            }
            statusBar.putClientProperty(GUARD_KEY, guard)
            statusBar.addHierarchyListener(guard)
        }
        return true
    }

    fun uninstall(project: Project) {
        val statusBar = statusBarComponent(project) ?: return
        (statusBar.getClientProperty(GUARD_KEY) as? HierarchyListener)?.let {
            statusBar.removeHierarchyListener(it)
            statusBar.putClientProperty(GUARD_KEY, null)
        }
        val host = statusBar.parent as? Host ?: return
        val parent = host.parent ?: return
        val constraints = (parent.layout as? BorderLayout)?.getConstraints(host) ?: BorderLayout.SOUTH
        val index = parent.components.indexOf(host)
        parent.remove(host)
        host.remove(statusBar)
        parent.add(statusBar, constraints, index)
        parent.revalidate()
        parent.repaint()
    }

    /** Sichtbar, wenn global eingeschaltet und nicht für dieses Projekt ausgeblendet. */
    fun isBarVisible(project: Project): Boolean =
        isBarVisible && !RunnersBarService.getInstance(project).isHiddenInProject

    fun applyVisibility(project: Project) {
        RunnersBarService.getInstance(project).panel.isVisible = isBarVisible(project)
    }

    private class Host(statusBar: JComponent, bar: JComponent) : JPanel(BorderLayout()) {
        init {
            isOpaque = false
            add(bar, BorderLayout.NORTH)
            add(statusBar, BorderLayout.CENTER)
        }
    }
}
