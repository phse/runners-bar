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

    /** Leiste nur in diesem Projekt ausgeblendet (unabhängig vom globalen Schalter). */
    var hidden: Boolean = false
}
