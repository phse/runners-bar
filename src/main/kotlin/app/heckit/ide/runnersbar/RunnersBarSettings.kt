package app.heckit.ide.runnersbar

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import kotlin.reflect.KMutableProperty0

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
    var showMenuArrow: Boolean = true
}

/** Darstellung, wie sie in einem Projekt tatsächlich gilt: eigener Wert des Projekts, sonst die Vorgabe. */
data class RunnersBarLayout(
    val position: BarPosition,
    val controlsAlignment: ControlsAlignment,
    val tabsAlignment: TabsAlignment,
    /** Pfeil fürs Menü an jedem Tab; das Menü gibt es per Rechtsklick auch ohne ihn. */
    val showMenuArrow: Boolean,
)

/** Vorgaben für die Darstellung der Leiste; jedes Projekt kann sie überschreiben (siehe [RunnersBarService]). */
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

    var showMenuArrow: Boolean
        get() = state.showMenuArrow
        set(value) {
            if (state.showMenuArrow == value) return
            state.showMenuArrow = value
            RunnersBarInstaller.reinstallAll()
        }

    companion object {
        fun getInstance(): RunnersBarSettings =
            ApplicationManager.getApplication().getService(RunnersBarSettings::class.java)
    }
}

/**
 * Settings | Appearance & Behavior | Runners Bar: oben die Vorgaben für alle Projekte, darunter die Abweichungen
 * des aktuellen Projekts. Im Default-Projekt (Einstellungen ohne offenes Projekt) nur die Vorgaben.
 */
class RunnersBarConfigurable(private val project: Project) : BoundConfigurable(RunnersBarBundle.message("settings.displayName")) {
    override fun createPanel(): DialogPanel {
        val settings = RunnersBarSettings.getInstance()
        return panel {
            group(RunnersBarBundle.message("settings.defaults")) {
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
                row {
                    checkBox(RunnersBarBundle.message("settings.menuArrow"))
                        .bindSelected(settings::showMenuArrow)
                        .comment(RunnersBarBundle.message("settings.menuArrow.comment"))
                }
            }
            if (!project.isDefault) projectGroup(RunnersBarService.getInstance(project))
        }
    }

    /** Je Einstellung eine Auswahl „Vorgabe“ oder ein fester Wert nur für dieses Projekt. */
    private fun Panel.projectGroup(service: RunnersBarService) {
        group(RunnersBarBundle.message("settings.project", project.name)) {
            overrideRow(RunnersBarBundle.message("settings.project.visible"), service::visible, listOf(true, false)) {
                RunnersBarBundle.message(if (it) "settings.shown" else "settings.hidden")
            }
            overrideRow(RunnersBarBundle.message("settings.position"), service::position, listOf(BarPosition.BOTTOM, BarPosition.TOP)) {
                RunnersBarBundle.message("settings.position.${it.name.lowercase()}")
            }
            overrideRow(RunnersBarBundle.message("settings.tabs"), service::tabsAlignment, TabsAlignment.entries) {
                RunnersBarBundle.message("settings.${it.name.lowercase()}")
            }
            overrideRow(RunnersBarBundle.message("settings.controls"), service::controlsAlignment, ControlsAlignment.entries) {
                RunnersBarBundle.message("settings.${it.name.lowercase()}")
            }
            overrideRow(RunnersBarBundle.message("settings.menuArrow"), service::showMenuArrow, listOf(true, false)) {
                RunnersBarBundle.message(if (it) "settings.shown" else "settings.hidden")
            }
        }
    }

    /** Auswahlliste mit „Vorgabe“ (null) und den möglichen Werten. */
    private fun <T : Any> Panel.overrideRow(label: String, property: KMutableProperty0<T?>, values: List<T>, text: (T) -> String) {
        val choices = listOf(Choice<T>(null, RunnersBarBundle.message("settings.useDefault"))) + values.map { Choice(it, text(it)) }
        row("$label:") {
            comboBox(choices, textListCellRenderer { it?.text })
                .bindItem({ choices.first { it.value == property.get() } }, { property.set(it?.value) })
        }
    }

    /** Eintrag der Auswahlliste; ohne null-Einträge, deren Darstellung die Plattform sonst selbst übernimmt. */
    private class Choice<T : Any>(val value: T?, val text: String)
}
