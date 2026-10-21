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
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
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

    /** Präfix in Run Anything, das aus einem Befehl einen Tab macht (z. B. „bar npm run dev“). */
    var runAnythingPrefix: String = DEFAULT_RUN_ANYTHING_PREFIX

    /** Beim Öffnen eines Projekts melden, wenn .vscode/tasks.json neue Tasks enthält. */
    var offerVsCodeTasks: Boolean = true

    companion object {
        const val DEFAULT_RUN_ANYTHING_PREFIX = "bar"
    }
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

    /** Gilt für alle Projekte; leer = Vorgabe. */
    var runAnythingPrefix: String
        get() = state.runAnythingPrefix.trim().ifEmpty { RunnersBarSettingsState.DEFAULT_RUN_ANYTHING_PREFIX }
        set(value) {
            state.runAnythingPrefix = value.trim()
        }

    var offerVsCodeTasks: Boolean
        get() = state.offerVsCodeTasks
        set(value) {
            state.offerVsCodeTasks = value
        }

    companion object {
        fun getInstance(): RunnersBarSettings =
            ApplicationManager.getApplication().getService(RunnersBarSettings::class.java)
    }
}

/**
 * Settings | Appearance & Behavior | Runners Bar: eine Tabelle mit den Vorgaben für alle Projekte und daneben den
 * Abweichungen des aktuellen Projekts, darunter die Gruppen des Projekts (Teilen) und Run Anything.
 * Im Default-Projekt (Einstellungen ohne offenes Projekt) nur die Vorgaben.
 */
class RunnersBarConfigurable(private val project: Project) : BoundConfigurable(RunnersBarBundle.message("settings.displayName")) {
    override fun createPanel(): DialogPanel {
        val settings = RunnersBarSettings.getInstance()
        val service = if (project.isDefault) null else RunnersBarService.getInstance(project)
        val shownHidden = listOf(true, false)
        fun shown(value: Boolean) = RunnersBarBundle.message(if (value) "settings.shown" else "settings.hidden")
        fun alignment(value: Enum<*>) = RunnersBarBundle.message("settings.${value.name.lowercase()}")
        return panel {
            group(RunnersBarBundle.message("settings.appearance")) {
                row(" ") {
                    label(RunnersBarBundle.message("settings.allProjects")).bold().widthGroup(DEFAULTS_COLUMN)
                    if (service != null) label(RunnersBarBundle.message("settings.project", project.name)).bold().widthGroup(PROJECT_COLUMN)
                }
                settingRow(
                    RunnersBarBundle.message("settings.project.visible"), shownHidden, ::shown,
                    { RunnersBarInstaller.isBarVisible }, { RunnersBarInstaller.setBarVisibleEverywhere(it) }, service?.let { it::visible },
                )
                settingRow(
                    RunnersBarBundle.message("settings.position"), listOf(BarPosition.BOTTOM, BarPosition.TOP),
                    { RunnersBarBundle.message("settings.position.${it.name.lowercase()}") },
                    settings::position.getter, settings::position.setter, service?.let { it::position },
                )
                settingRow(
                    RunnersBarBundle.message("settings.tabs"), TabsAlignment.entries, ::alignment,
                    settings::tabsAlignment.getter, settings::tabsAlignment.setter, service?.let { it::tabsAlignment },
                )
                settingRow(
                    RunnersBarBundle.message("settings.controls"), ControlsAlignment.entries, ::alignment,
                    settings::controlsAlignment.getter, settings::controlsAlignment.setter, service?.let { it::controlsAlignment },
                )
                settingRow(
                    RunnersBarBundle.message("settings.menuArrow"), shownHidden, ::shown,
                    settings::showMenuArrow.getter, settings::showMenuArrow.setter, service?.let { it::showMenuArrow },
                ).rowComment(RunnersBarBundle.message("settings.menuArrow.comment"))
            }
            if (service != null) groupsGroup(service)
            if (RunnersBarShell.isAvailable) {
                group(RunnersBarBundle.message("settings.vscode")) {
                    row {
                        checkBox(RunnersBarBundle.message("settings.vscode.offer"))
                            .bindSelected(settings::offerVsCodeTasks)
                            .comment(RunnersBarBundle.message("settings.vscode.comment"))
                    }
                }
            }
            group(RunnersBarBundle.message("settings.runAnything")) {
                row(RunnersBarBundle.message("settings.runAnything.prefix")) {
                    textField()
                        .columns(10)
                        .bindText(settings::runAnythingPrefix)
                        .comment(RunnersBarBundle.message("settings.runAnything.comment"))
                }
            }
        }
    }

    /** Je Gruppe ein Häkchen „Mit dem Team teilen“. */
    private fun Panel.groupsGroup(service: RunnersBarService) {
        group(RunnersBarBundle.message("settings.groups", project.name)) {
            service.groupNames.forEach { name ->
                val display = if (name == RunnersBarGroup.DEFAULT_GROUP) RunnersBarBundle.message("group.default") else name
                row("$display:") {
                    checkBox(RunnersBarBundle.message("settings.groups.share"))
                        .bindSelected({ service.isShared(name) }, { service.setShared(name, it) })
                }
            }
            row { comment(RunnersBarBundle.message("settings.groups.comment")) }
        }
    }

    /**
     * Eine Zeile der Tabelle: Vorgabe für alle Projekte und, falls ein Projekt offen ist, sein eigener Wert
     * („Wie alle Projekte“ = null).
     */
    private fun <T : Any> Panel.settingRow(
        label: String,
        values: List<T>,
        text: (T) -> String,
        getDefault: () -> T,
        setDefault: (T) -> Unit,
        projectValue: KMutableProperty0<T?>?,
    ) = row("$label:") {
        val defaults = values.map { Choice(it, text(it)) }
        comboBox(defaults, textListCellRenderer { it?.text })
            .bindItem({ defaults.first { it.value == getDefault() } }, { it?.value?.let(setDefault) })
            .widthGroup(DEFAULTS_COLUMN)
        if (projectValue != null) {
            val choices = listOf(Choice<T>(null, RunnersBarBundle.message("settings.useDefault"))) + defaults
            comboBox(choices, textListCellRenderer { it?.text })
                .bindItem({ choices.first { it.value == projectValue.get() } }, { projectValue.set(it?.value) })
                .widthGroup(PROJECT_COLUMN)
        }
    }

    private companion object {
        // Gleiche Breite je Spalte, damit die Tabelle bündig ist.
        const val DEFAULTS_COLUMN = "RunnersBar.defaults"
        const val PROJECT_COLUMN = "RunnersBar.project"
    }

    /** Eintrag der Auswahlliste; ohne null-Einträge, deren Darstellung die Plattform sonst selbst übernimmt. */
    private class Choice<T : Any>(val value: T?, val text: String)
}
