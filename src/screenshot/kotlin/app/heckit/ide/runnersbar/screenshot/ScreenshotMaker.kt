package app.heckit.ide.runnersbar.screenshot

import app.heckit.ide.runnersbar.RunnersBarExecution
import app.heckit.ide.runnersbar.RunnersBarService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

/**
 * Erzeugt die Marketplace-Screenshots in der Sandbox-IDE (./gradlew runIde -Pscreenshots=...).
 * Zeichnet Frame und Popups selbst in ein Bild, braucht also keine Bildschirmaufnahme-Rechte.
 * Ist nur in Builds mit -Pscreenshots enthalten.
 */
object ScreenshotMaker {
    private const val SCALE = 2.0
    private val log = logger<ScreenshotMaker>()

    @JvmStatic
    fun start(project: Project, dir: String) {
        val out = File(dir).apply { mkdirs() }
        Thread({
            try {
                run(project, out)
            } catch (e: Throwable) {
                log.error("Screenshots failed", e)
            }
        }, "RunnersBar screenshots").start()
    }

    private fun run(project: Project, out: File) {
        val frame = edt { WindowManager.getInstance().getFrame(project) } ?: error("no frame")
        edt {
            frame.extendedState = JFrame.NORMAL
            frame.setBounds(60, 40, 1280, 800)
            project.guessProjectDir()?.findFileByRelativePath("src/server.js")?.let {
                FileEditorManager.getInstance(project).openFile(it, true)
            }
        }
        Thread.sleep(6000)

        val panel = RunnersBarService.getInstance(project).panel
        edt {
            val dev = RunnersBarService.getInstance(project).entries.first { it.name == "dev" }
            RunnersBarService.getInstance(project).findSettings(dev)?.let { RunnersBarExecution.execute(it, false) }
        }
        Thread.sleep(5000)
        shot(frame, File(out, "01-runners-bar.png"))

        popupShot(frame, File(out, "02-tab-menu.png")) {
            panel.tabComponents.first { it.entry.name == "test" }.showMenu()
        }
        popupShot(frame, File(out, "03-groups.png")) { panel.showGroupPopup(panel.groupSwitcher) }
        popupShot(frame, File(out, "04-add.png")) { panel.showAddPopup(panel.addButton) }
        log.warn("Runners Bar: screenshots written to $out")
    }

    private fun popupShot(frame: JFrame, file: File, open: () -> JBPopup) {
        val popup = edt(open)
        Thread.sleep(1500)
        shot(frame, file)
        edt { popup.cancel() }
        Thread.sleep(500)
    }

    /** Zeichnet den Frame und alle darüber liegenden Fenster (Popups) in ein PNG. */
    private fun shot(frame: JFrame, file: File) = edt {
        val root = frame.rootPane
        val origin = root.locationOnScreen
        val image = BufferedImage((root.width * SCALE).toInt(), (root.height * SCALE).toInt(), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.scale(SCALE, SCALE)
        root.paint(g)
        for (window in Window.getWindows()) {
            if (window === frame || !window.isShowing || window !is RootPaneContainer) continue
            if (SwingUtilities.getWindowAncestor(window) == null && window.owner == null) continue
            val pos = window.locationOnScreen
            val wg = g.create(pos.x - origin.x, pos.y - origin.y, window.width, window.height)
            window.rootPane.paint(wg)
            wg.dispose()
            // Rahmen und Schatten des Popups zeichnet sonst das Betriebssystem.
            g.color = JBColor.border()
            g.drawRoundRect(pos.x - origin.x, pos.y - origin.y, window.width - 1, window.height - 1, 8, 8)
        }
        g.dispose()
        ImageIO.write(image, "png", file)
    }

    private fun <T> edt(block: () -> T): T {
        var result: Result<T>? = null
        ApplicationManager.getApplication().invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
