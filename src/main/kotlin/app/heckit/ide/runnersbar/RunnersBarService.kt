package app.heckit.ide.runnersbar

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.impl.RunDialog
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.execution.process.ProcessHandler
import com.intellij.ide.actions.runAnything.execution.RunAnythingRunProfile
import com.intellij.execution.runners.ExecutionEnvironment
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

/** Hält Gruppen und Einträge der Runners Bar eines Projekts: persönliche in workspace.xml, geteilte in [RunnersBarSharedService]. */
@Service(Service.Level.PROJECT)
@State(name = "RunnersBar", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class RunnersBarService(private val project: Project) : PersistentStateComponent<RunnersBarState>, Disposable {

    private var state = RunnersBarState()

    /**
     * Zuletzt gestartete Konfigurationen, die die IDE nicht kennt: z. B. startet Run Anything „npm run dev“ über eine
     * Konfiguration, die nie im RunManager landet, also auch nicht unter den temporären. Nur im Speicher.
     */
    private val unsavedRuns = ArrayDeque<RunnerAndConfigurationSettings>()

    /** Shell-Befehl, den Run Anything ohne Konfiguration ausgeführt hat (z. B. „echo x“). */
    data class CommandRun(val command: String, val workingDirectory: String?)

    private val commandRuns = ArrayDeque<CommandRun>()

    /** Zuletzt in Run Anything ausgeführte Shell-Befehle; leer ohne Shell-Script-Plugin. */
    val recentCommands: List<CommandRun> get() = if (RunnersBarShell.isAvailable) commandRuns.toList() else emptyList()

    /** Macht aus einem Befehl eine Shell-Script-Konfiguration namens [name] und nimmt sie in die Leiste auf. */
    fun keep(run: CommandRun, name: String) {
        commandRuns.remove(run)
        add(RunnersBarShell.create(project, run.command, run.workingDirectory, name))
    }

    init {
        project.messageBus.connect(this).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                (env.runProfile as? RunAnythingRunProfile)?.let { profile ->
                    val run = CommandRun(profile.originalCommand, profile.commandLine.workDirectory?.path)
                    commandRuns.remove(run)
                    commandRuns.addFirst(run)
                    while (commandRuns.size > MAX_UNSAVED_RUNS) commandRuns.removeLast()
                    return
                }
                val settings = env.runnerAndConfigurationSettings ?: return
                if (isRegistered(settings)) return
                unsavedRuns.removeIf { it === settings || it.uniqueID == settings.uniqueID }
                unsavedRuns.addFirst(settings)
                while (unsavedRuns.size > MAX_UNSAVED_RUNS) unsavedRuns.removeLast()
            }
        })
    }

    private fun isRegistered(settings: RunnerAndConfigurationSettings): Boolean =
        RunManager.getInstance(project).allSettings.any { it === settings }

    /** Temporäre Konfigurationen der IDE und zuletzt gestartete, die sie nicht kennt; neueste zuerst. */
    val temporarySettings: List<RunnerAndConfigurationSettings>
        get() {
            unsavedRuns.removeIf { isRegistered(it) }
            return unsavedRuns + RunManager.getInstance(project).tempConfigurationsList.filter { !contains(it) }
        }

    /** Behält eine temporäre oder unbekannte Konfiguration dauerhaft unter [name] und nimmt sie in die Leiste auf. */
    fun keep(settings: RunnerAndConfigurationSettings, name: String) {
        if (isRegistered(settings)) {
            rename(settings, name)
        } else {
            unsavedRuns.remove(settings)
            settings.name = name
            settings.isTemporary = false
            RunManager.getInstance(project).addConfiguration(settings)
        }
        add(settings)
    }

    /** Die Leiste lebt so lange wie das Projekt und wird bei Bedarf neu in den Frame gehängt. */
    val panel: RunnersBarPanel by lazy { RunnersBarPanel(project, this) }

    override fun getState(): RunnersBarState = state

    override fun loadState(loaded: RunnersBarState) {
        if (loaded.hidden) {
            loaded.hidden = false
            loaded.visible = false
        }
        state = loaded
        fireChanged()
    }

    /** Sichtbarkeit nur in diesem Projekt; null = Vorgabe aus View | Appearance | Runners Bar. */
    var visible: Boolean?
        get() = state.visible
        set(value) {
            if (state.visible == value) return
            state.visible = value
            RunnersBarInstaller.applyVisibility(project)
        }

    val isVisible: Boolean get() = state.visible ?: RunnersBarInstaller.isBarVisible

    /** Ein- oder ausblenden; entspricht das der Vorgabe, folgt das Projekt wieder der Vorgabe. */
    fun setVisibleInProject(visible: Boolean) {
        this.visible = visible.takeUnless { it == RunnersBarInstaller.isBarVisible }
    }

    // ---- Darstellung: eigene Werte dieses Projekts, null = Vorgabe aus RunnersBarSettings ----

    val layout: RunnersBarLayout
        get() {
            val defaults = RunnersBarSettings.getInstance()
            return RunnersBarLayout(
                position = state.position ?: defaults.position,
                controlsAlignment = state.controlsAlignment ?: defaults.controlsAlignment,
                tabsAlignment = state.tabsAlignment ?: defaults.tabsAlignment,
                showMenuArrow = state.showMenuArrow ?: defaults.showMenuArrow,
            )
        }

    var position: BarPosition?
        get() = state.position
        set(value) = changeLayout(state.position, value) { state.position = it }

    var controlsAlignment: ControlsAlignment?
        get() = state.controlsAlignment
        set(value) = changeLayout(state.controlsAlignment, value) { state.controlsAlignment = it }

    var tabsAlignment: TabsAlignment?
        get() = state.tabsAlignment
        set(value) = changeLayout(state.tabsAlignment, value) { state.tabsAlignment = it }

    var showMenuArrow: Boolean?
        get() = state.showMenuArrow
        set(value) = changeLayout(state.showMenuArrow, value) { state.showMenuArrow = it }

    private fun <T> changeLayout(old: T, new: T, assign: (T) -> Unit) {
        if (old == new) return
        assign(new)
        RunnersBarInstaller.install(project)
    }

    // ---- Gruppen ----

    private val shared get() = RunnersBarSharedService.getInstance(project).state

    /** Persönliche Gruppen zuerst, dann die geteilten. */
    private val groups: List<RunnersBarGroup> get() = state.groups + shared.groups

    private fun activeGroup(): RunnersBarGroup {
        if (groups.isEmpty()) state.groups.add(RunnersBarGroup())
        return groups.firstOrNull { it.name == state.activeGroup }
            ?: groups.first()
    }

    val groupNames: List<String>
        get() {
            activeGroup()
            return groups.map { it.name }
        }

    val activeGroupName: String get() = activeGroup().name

    fun hasGroup(name: String): Boolean = groups.any { it.name == name }

    fun isShared(name: String): Boolean = shared.groups.any { it.name == name }

    /**
     * Verschiebt eine Gruppe in die geteilte Datei (.idea/runnersBar.xml) oder zurück in den Workspace. Beim Teilen werden
     * ihre Konfigurationen als Projektdateien gespeichert, sonst fehlen sie den anderen.
     */
    fun setShared(name: String, share: Boolean) {
        if (isShared(name) == share) return
        val from = if (share) state.groups else shared.groups
        val group = from.firstOrNull { it.name == name } ?: return
        from.remove(group)
        (if (share) shared.groups else state.groups).add(group)
        if (share) group.entries.forEach { entry -> findSettings(entry)?.let(::storeForTeam) }
        fireChanged()
    }

    /** Konfigurationen, die nur im persönlichen Workspace liegen, die geteilte Gruppen aber referenzieren. */
    fun unsharedConfigurations(name: String): List<RunnerAndConfigurationSettings> =
        groups.firstOrNull { it.name == name }?.entries.orEmpty()
            .mapNotNull { findSettings(it) }
            .filter { it.isStoredInLocalWorkspace }

    /** Wie „Store as project file“ im Run-Dialog: erst nach erneutem addConfiguration schreibt die IDE die Datei. */
    private fun storeForTeam(settings: RunnerAndConfigurationSettings) {
        if (!settings.isStoredInLocalWorkspace) return
        settings.storeInDotIdeaFolder()
        RunManager.getInstance(project).addConfiguration(settings)
    }

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
        val group = groups.firstOrNull { it.name == oldName } ?: return
        group.name = newName
        if (state.activeGroup == oldName) state.activeGroup = newName
        fireChanged()
    }

    fun removeGroup(name: String) {
        val all = groups
        if (all.size <= 1) return
        val index = all.indexOfFirst { it.name == name }
        if (index < 0) return
        if (!state.groups.remove(all[index])) shared.groups.remove(all[index])
        if (state.activeGroup == name) state.activeGroup = groups[(index - 1).coerceAtLeast(0)].name
        fireChanged()
    }

    /** Nimmt [settings] in die Gruppe [name] auf (legt sie bei Bedarf als persönliche an), ohne umzuschalten. */
    fun addToGroup(name: String, settings: RunnerAndConfigurationSettings) {
        activeGroup()
        val group = groups.firstOrNull { it.name == name } ?: RunnersBarGroup().also {
            it.name = name
            state.groups.add(it)
        }
        if (group.entries.none { it.configId == settings.uniqueID }) {
            group.entries.add(RunnersBarEntry().apply {
                configId = settings.uniqueID
                this.name = settings.name
            })
        }
        fireChanged()
    }

    /** Liegt die Konfiguration von [entry] schon in der Gruppe [name]? */
    fun groupContains(name: String, entry: RunnersBarEntry): Boolean =
        groups.firstOrNull { it.name == name }?.entries?.any { it.configId == entry.configId } == true

    /** Legt eine persönliche Gruppe an, ohne auf sie umzuschalten. */
    fun createGroup(name: String) {
        if (name.isBlank() || hasGroup(name)) return
        activeGroup()
        state.groups.add(RunnersBarGroup().also { it.name = name })
        fireChanged()
    }

    /** Verschiebt [entry] aus der aktiven Gruppe ans Ende von [target] oder kopiert ihn dorthin ([copy]). */
    fun moveToGroup(entry: RunnersBarEntry, target: String, copy: Boolean): Boolean {
        val to = groups.firstOrNull { it.name == target } ?: return false
        if (to === activeGroup() || groupContains(target, entry)) return false
        to.entries.add(RunnersBarEntry().apply {
            configId = entry.configId
            name = entry.name
            debug = entry.debug
        })
        if (isShared(target)) findSettings(entry)?.let(::storeForTeam)
        if (!copy) activeGroup().entries.remove(entry)
        fireChanged()
        return true
    }

    /** Macht [moveToGroup] rückgängig: aus [target] entfernen und beim Verschieben an [index] in [source] zurücklegen. */
    fun undoMoveToGroup(entry: RunnersBarEntry, source: String, index: Int, target: String, copy: Boolean) {
        groups.firstOrNull { it.name == target }?.entries?.removeIf { it.configId == entry.configId }
        if (!copy) {
            groups.firstOrNull { it.name == source }?.entries
                ?.takeIf { list -> list.none { it.configId == entry.configId } }
                ?.let { it.add(index.coerceIn(0, it.size), entry) }
        }
        fireChanged()
    }

    // ---- VS Code Tasks ----

    /** Konfiguration eines übernommenen Tasks; null, wenn er nie übernommen oder die Konfiguration gelöscht wurde. */
    fun importedSettings(key: String): RunnerAndConfigurationSettings? =
        state.vsCodeTasks.firstOrNull { it.key == key }
            ?.let { imported -> RunManager.getInstance(project).allSettings.firstOrNull { it.uniqueID == imported.configId } }

    fun markImported(key: String, settings: RunnerAndConfigurationSettings) {
        state.vsCodeTasks.removeIf { it.key == key }
        state.vsCodeTasks.add(RunnersBarImportedTask().also {
            it.key = key
            it.configId = settings.uniqueID
        })
    }

    /** Tasks, über die noch nicht entschieden wurde (importiert oder bewusst weggelassen). */
    fun notOffered(keys: List<String>): List<String> = keys.filter { it !in state.offeredVsCodeTasks }

    fun markOffered(keys: List<String>) {
        state.offeredVsCodeTasks.addAll(notOffered(keys))
    }

    // ---- Einträge der aktiven Gruppe ----

    val entries: List<RunnersBarEntry> get() = activeGroup().entries.toList()

    fun findSettings(entry: RunnersBarEntry): RunnerAndConfigurationSettings? =
        RunManager.getInstance(project).allSettings.firstOrNull { it.uniqueID == entry.configId }

    fun contains(settings: RunnerAndConfigurationSettings): Boolean =
        activeGroup().entries.any { it.configId == settings.uniqueID }

    /** Temporäre Konfigurationen (z. B. aus Run Anything) werden dabei dauerhaft, sonst räumt die IDE sie wieder ab. */
    fun add(settings: RunnerAndConfigurationSettings) {
        if (settings.isTemporary) RunManager.getInstance(project).makeStable(settings)
        if (contains(settings)) return
        if (isShared(activeGroupName)) storeForTeam(settings)
        activeGroup().entries.add(RunnersBarEntry().apply {
            configId = settings.uniqueID
            name = settings.name
        })
        fireChanged()
    }

    /** Ersetzt [old] durch [new] (Name bleibt, Platz in der Leiste auch) und entfernt die alte Konfiguration. */
    fun replace(entry: RunnersBarEntry, old: RunnerAndConfigurationSettings, new: RunnerAndConfigurationSettings) {
        val runManager = RunManager.getInstance(project)
        if (new.isTemporary) runManager.makeStable(new)
        if (runManager.allSettings.none { it === new }) {
            new.isTemporary = false
            runManager.addConfiguration(new)
        }
        runManager.removeConfiguration(old)
        rename(new, old.name)
        entry.configId = new.uniqueID
        entry.name = new.name
        fireChanged()
    }

    /** Umbenennen über addConfiguration: die IDE führt die ID nach und meldet die Änderung, siehe [configurationChanged]. */
    fun rename(settings: RunnerAndConfigurationSettings, newName: String) {
        if (settings.name == newName) return
        settings.name = newName
        RunManager.getInstance(project).addConfiguration(settings)
    }

    fun remove(entry: RunnersBarEntry) {
        if (activeGroup().entries.remove(entry)) fireChanged()
    }

    /** Löscht die Konfiguration aus der IDE und entfernt ihre Einträge aus allen Gruppen. */
    fun delete(settings: RunnerAndConfigurationSettings) {
        val id = settings.uniqueID
        RunManager.getInstance(project).removeConfiguration(settings)
        groups.forEach { group -> group.entries.removeIf { it.configId == id } }
        fireChanged()
    }

    fun setDebug(entry: RunnersBarEntry, debug: Boolean) {
        if (entry.debug == debug) return
        entry.debug = debug
        fireUpdated(entry)
    }

    /** Kopiert [settings], öffnet den Editor der Kopie und reiht sie nach „OK“ direkt hinter [original] ein (mit dessen Debug-Modus). */
    fun duplicate(original: RunnersBarEntry, settings: RunnerAndConfigurationSettings, title: String) {
        val runManager = RunManager.getInstance(project)
        val copy = runManager.createConfiguration(settings.configuration.clone(), settings.factory)
        copy.name = runManager.suggestUniqueName(settings.name, settings.type)
        if (!RunDialog.editConfiguration(project, copy, title)) return
        runManager.addConfiguration(copy)
        add(copy)
        val index = activeGroup().entries.indexOf(original)
        val entry = activeGroup().entries.firstOrNull { it.configId == copy.uniqueID } ?: return
        entry.debug = original.debug
        if (index >= 0) moveTo(entry, index + 1)
        fireChanged()
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
        for (group in groups) {
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

    internal fun fireChanged() {
        if (!project.isDisposed) project.messageBus.syncPublisher(RunnersBarListener.TOPIC).entriesChanged()
    }

    private fun fireUpdated(entry: RunnersBarEntry) {
        if (!project.isDisposed) project.messageBus.syncPublisher(RunnersBarListener.TOPIC).entryUpdated(entry)
    }

    override fun dispose() {
        RunnersBarInstaller.uninstall(project)
    }

    companion object {
        private const val MAX_UNSAVED_RUNS = 10

        fun getInstance(project: Project): RunnersBarService = project.getService(RunnersBarService::class.java)
    }
}

/** Geteilte Gruppen; ändert sich die Datei von außen (z. B. git pull), wird die Leiste neu aufgebaut. */
@Service(Service.Level.PROJECT)
@State(name = "RunnersBarShared", storages = [Storage("runnersBar.xml")])
class RunnersBarSharedService(private val project: Project) : PersistentStateComponent<RunnersBarSharedState> {

    private var state = RunnersBarSharedState()

    override fun getState(): RunnersBarSharedState = state

    override fun loadState(loaded: RunnersBarSharedState) {
        state = loaded
        if (!project.isDisposed) project.messageBus.syncPublisher(RunnersBarListener.TOPIC).entriesChanged()
    }

    companion object {
        fun getInstance(project: Project): RunnersBarSharedService = project.getService(RunnersBarSharedService::class.java)
    }
}
