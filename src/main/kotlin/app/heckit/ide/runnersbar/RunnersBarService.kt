package app.heckit.ide.runnersbar

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

interface RunnersBarListener {
    /** Einträge oder Gruppen haben sich geändert: Leiste neu aufbauen. */
    fun entriesChanged() {}

    /** Nur die Darstellung eines Eintrags hat sich geändert (z. B. Debug-Modus). */
    fun entryUpdated(entry: RunnersBarEntry) {}

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic(RunnersBarListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/** Hält Gruppen und Einträge der Runners Bar eines Projekts (persönlich, daher in workspace.xml). */
@Service(Service.Level.PROJECT)
@State(name = "RunnersBar", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class RunnersBarService(private val project: Project) : PersistentStateComponent<RunnersBarState>, Disposable {

    private var state = RunnersBarState()

    /** Die Leiste lebt so lange wie das Projekt und wird bei Bedarf neu in den Frame gehängt. */
    val panel: RunnersBarPanel by lazy { RunnersBarPanel(project, this) }

    override fun getState(): RunnersBarState = state

    override fun loadState(loaded: RunnersBarState) {
        state = loaded
        fireChanged()
    }

    /** Leiste nur in diesem Projekt ausblenden; der globale Schalter in View | Appearance bleibt davon unberührt. */
    var isHiddenInProject: Boolean
        get() = state.hidden
        set(value) {
            if (state.hidden == value) return
            state.hidden = value
            RunnersBarInstaller.applyVisibility(project)
        }

    // ---- Gruppen ----

    private fun activeGroup(): RunnersBarGroup {
        if (state.groups.isEmpty()) state.groups.add(RunnersBarGroup())
        return state.groups.firstOrNull { it.name == state.activeGroup }
            ?: state.groups.first().also { state.activeGroup = it.name }
    }

    val groupNames: List<String>
        get() {
            activeGroup()
            return state.groups.map { it.name }
        }

    val activeGroupName: String get() = activeGroup().name

    fun hasGroup(name: String): Boolean = state.groups.any { it.name == name }

    fun selectGroup(name: String) {
        if (name == activeGroupName || !hasGroup(name)) return
        state.activeGroup = name
        fireChanged()
    }

    /** Legt eine Gruppe an und schaltet direkt auf sie um. */
    fun addGroup(name: String) {
        if (name.isBlank() || hasGroup(name)) return
        activeGroup()
        state.groups.add(RunnersBarGroup().also { it.name = name })
        state.activeGroup = name
        fireChanged()
    }

    fun renameGroup(oldName: String, newName: String) {
        if (newName.isBlank() || hasGroup(newName)) return
        val group = state.groups.firstOrNull { it.name == oldName } ?: return
        group.name = newName
        if (state.activeGroup == oldName) state.activeGroup = newName
        fireChanged()
    }

    fun removeGroup(name: String) {
        if (state.groups.size <= 1) return
        val index = state.groups.indexOfFirst { it.name == name }
        if (index < 0) return
        state.groups.removeAt(index)
        if (state.activeGroup == name) state.activeGroup = state.groups[(index - 1).coerceAtLeast(0)].name
        fireChanged()
    }

    // ---- Einträge der aktiven Gruppe ----

    val entries: List<RunnersBarEntry> get() = activeGroup().entries.toList()

    fun findSettings(entry: RunnersBarEntry): RunnerAndConfigurationSettings? =
        RunManager.getInstance(project).allSettings.firstOrNull { it.uniqueID == entry.configId }

    fun contains(settings: RunnerAndConfigurationSettings): Boolean =
        activeGroup().entries.any { it.configId == settings.uniqueID }

    fun add(settings: RunnerAndConfigurationSettings) {
        if (contains(settings)) return
        activeGroup().entries.add(RunnersBarEntry().apply {
            configId = settings.uniqueID
            name = settings.name
        })
        fireChanged()
    }

    fun remove(entry: RunnersBarEntry) {
        if (activeGroup().entries.remove(entry)) fireChanged()
    }

    fun setDebug(entry: RunnersBarEntry, debug: Boolean) {
        if (entry.debug == debug) return
        entry.debug = debug
        fireUpdated(entry)
    }

    fun move(entry: RunnersBarEntry, delta: Int) {
        val index = activeGroup().entries.indexOf(entry)
        if (index < 0) return
        // Beim Verschieben nach rechts liegt die Einfügeposition hinter dem Nachbarn.
        moveTo(entry, if (delta > 0) index + delta + 1 else index + delta)
    }

    /** Verschiebt [entry] an die Einfügeposition [slot] (0 = ganz vorne, size = ganz hinten). */
    fun moveTo(entry: RunnersBarEntry, slot: Int) {
        val list = activeGroup().entries
        val from = list.indexOf(entry)
        if (from < 0) return
        val to = (if (slot > from) slot - 1 else slot).coerceIn(0, list.size - 1)
        if (to == from) return
        list.removeAt(from)
        list.add(to, entry)
        fireChanged()
    }

    /** Wird bei Umbenennungen aufgerufen, damit Einträge in allen Gruppen an der Konfiguration hängen bleiben. */
    fun configurationChanged(settings: RunnerAndConfigurationSettings, oldId: String?) {
        var changed = false
        for (group in state.groups) {
            for (entry in group.entries) {
                if (entry.configId == settings.uniqueID || (oldId != null && entry.configId == oldId)) {
                    if (entry.configId != settings.uniqueID || entry.name != settings.name) {
                        entry.configId = settings.uniqueID
                        entry.name = settings.name
                        changed = true
                    }
                }
            }
        }
        if (changed) fireChanged()
    }

    private fun fireChanged() {
        if (!project.isDisposed) project.messageBus.syncPublisher(RunnersBarListener.TOPIC).entriesChanged()
    }

    private fun fireUpdated(entry: RunnersBarEntry) {
        if (!project.isDisposed) project.messageBus.syncPublisher(RunnersBarListener.TOPIC).entryUpdated(entry)
    }

    override fun dispose() {
        RunnersBarInstaller.uninstall(project)
    }

    companion object {
        fun getInstance(project: Project): RunnersBarService = project.getService(RunnersBarService::class.java)
    }
}
