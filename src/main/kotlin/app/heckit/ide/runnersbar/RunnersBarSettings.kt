package app.heckit.ide.runnersbar

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/** Oben: zwischen Toolbar und Tool-Fenstern. Unten: direkt über der Statuszeile. */
enum class BarPosition { TOP, BOTTOM }

/** Seite der Bedienelemente („+“, Gruppen-Umschalter). */
enum class ControlsAlignment { LEFT, RIGHT }

/** Ausrichtung der Tabs im übrigen Platz. */
enum class TabsAlignment { LEFT, CENTER, RIGHT }

class RunnersBarSettingsState {
    var position: BarPosition = BarPosition.BOTTOM
    var controlsAlignment: ControlsAlignment = ControlsAlignment.LEFT
    var tabsAlignment: TabsAlignment = TabsAlignment.LEFT
}

/** Darstellung der Leiste, gilt für alle Projekte. */
@Service(Service.Level.APP)
@State(name = "RunnersBarSettings", storages = [Storage("runnersBar.xml")])
class RunnersBarSettings : PersistentStateComponent<RunnersBarSettingsState> {

    private var state = RunnersBarSettingsState()

    override fun getState(): RunnersBarSettingsState = state

    override fun loadState(loaded: RunnersBarSettingsState) {
        state = loaded
    }

    var position: BarPosition
        get() = state.position
        set(value) {
            if (state.position == value) return
            state.position = value
            RunnersBarInstaller.reinstallAll()
        }

    var controlsAlignment: ControlsAlignment
        get() = state.controlsAlignment
        set(value) {
            if (state.controlsAlignment == value) return
            state.controlsAlignment = value
            RunnersBarInstaller.reinstallAll()
        }

    var tabsAlignment: TabsAlignment
        get() = state.tabsAlignment
        set(value) {
            if (state.tabsAlignment == value) return
            state.tabsAlignment = value
            RunnersBarInstaller.reinstallAll()
        }

    companion object {
        fun getInstance(): RunnersBarSettings =
            ApplicationManager.getApplication().getService(RunnersBarSettings::class.java)
    }
}

/** Settings | Appearance & Behavior | Runners Bar */
class RunnersBarConfigurable : BoundConfigurable(RunnersBarBundle.message("settings.displayName")) {
    override fun createPanel(): DialogPanel {
        val settings = RunnersBarSettings.getInstance()
        return panel {
            row {
                checkBox(RunnersBarBundle.message("settings.visible"))
                    .bindSelected({ RunnersBarInstaller.isBarVisible }, { RunnersBarInstaller.setBarVisibleEverywhere(it) })
            }
            buttonsGroup(RunnersBarBundle.message("settings.position")) {
                row { radioButton(RunnersBarBundle.message("settings.position.bottom"), BarPosition.BOTTOM) }
                row { radioButton(RunnersBarBundle.message("settings.position.top"), BarPosition.TOP) }
            }.bind(settings::position)
            buttonsGroup(RunnersBarBundle.message("settings.tabs")) {
                row { radioButton(RunnersBarBundle.message("settings.left"), TabsAlignment.LEFT) }
                row { radioButton(RunnersBarBundle.message("settings.center"), TabsAlignment.CENTER) }
                row { radioButton(RunnersBarBundle.message("settings.right"), TabsAlignment.RIGHT) }
            }.bind(settings::tabsAlignment)
            buttonsGroup(RunnersBarBundle.message("settings.controls")) {
                row { radioButton(RunnersBarBundle.message("settings.left"), ControlsAlignment.LEFT) }
                row { radioButton(RunnersBarBundle.message("settings.right"), ControlsAlignment.RIGHT) }
            }.bind(settings::controlsAlignment)
        }
    }
}
