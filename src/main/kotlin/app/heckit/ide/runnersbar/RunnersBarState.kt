package app.heckit.ide.runnersbar

/** Eine Run Configuration in der Leiste, referenziert über [com.intellij.execution.RunnerAndConfigurationSettings.getUniqueID]. */
class RunnersBarEntry {
    var configId: String = ""

    /** Zuletzt bekannter Name, damit verwaiste Einträge noch lesbar angezeigt werden. */
    var name: String = ""

    /** Ein Klick startet im Debug-Modus statt normal. */
    var debug: Boolean = false
}

/** Vorbereitet für spätere Gruppen: aktuell gibt es genau eine Gruppe. */
class RunnersBarGroup {
    var name: String = DEFAULT_GROUP
    var entries: MutableList<RunnersBarEntry> = mutableListOf()

    companion object {
        const val DEFAULT_GROUP = "Default"
    }
}

class RunnersBarState {
    var groups: MutableList<RunnersBarGroup> = mutableListOf()
    var activeGroup: String = RunnersBarGroup.DEFAULT_GROUP

    /** Bis 1.0.0: Leiste in diesem Projekt ausgeblendet. Wird beim Laden in [visible] übernommen. */
    var hidden: Boolean = false

    // Sichtbarkeit und Darstellung nur für dieses Projekt; null = Vorgabe.
    var visible: Boolean? = null
    var position: BarPosition? = null
    var controlsAlignment: ControlsAlignment? = null
    var tabsAlignment: TabsAlignment? = null
    var showMenuArrow: Boolean? = null
}
