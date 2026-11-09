package app.heckit.ide.runnersbar

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import java.awt.event.MouseEvent

/**
 * Run-Icon im Gutter von `.runners-bar` neben jedem Runner (am Schlüssel `"name"`, ohne Namen an `"command"`). Ein Klick
 * speichert die Datei, gleicht die Leiste ab und startet den Runner - Debug-Modus des Tabs gilt hier nicht.
 */
class RunnersBarFileLineMarker : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null) return null
        if (element.containingFile?.name != RunnersBarFile.FILE_NAME) return null
        val literal = element.parent as? JsonStringLiteral ?: return null
        val property = literal.parent as? JsonProperty ?: return null
        if (property.nameElement != literal) return null
        val runner = runner(property) ?: return null
        val (group, label) = runner
        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.RunConfigurations.TestState.Run,
            { RunnersBarBundle.message("file.gutter.run", label) },
            GutterIconNavigationHandler<PsiElement> { _: MouseEvent, psi -> run(psi, group, label) },
            GutterIconRenderer.Alignment.CENTER,
            { RunnersBarBundle.message("file.gutter.run", label) },
        )
    }

    /** Gruppe und Name des Runners, an dessen Schlüssel [property] das Icon steht; sonst null. */
    private fun runner(property: JsonProperty): Pair<String, String>? {
        val runnerObject = property.parent as? JsonObject ?: return null
        val name = runnerObject.string("name")
        val command = runnerObject.string("command") ?: return null
        when (property.name) {
            "name" -> if (name == null) return null
            "command" -> if (name != null) return null
            else -> return null
        }
        val runners = (runnerObject.parent as? JsonArray)?.parent as? JsonProperty ?: return null
        if (runners.name != "runners") return null
        val groupObject = runners.parent as? JsonObject ?: return null
        val groups = (groupObject.parent as? JsonArray)?.parent as? JsonProperty ?: return null
        if (groups.name != "groups") return null
        return (groupObject.string("name") ?: RunnersBarGroup.DEFAULT_GROUP) to (name ?: command)
    }

    private fun run(psi: PsiElement, group: String, label: String) {
        val project = psi.project
        val file = psi.containingFile?.virtualFile ?: return
        // Ungespeicherte Änderungen zuerst übernehmen, damit der Klick genau das startet, was im Editor steht.
        FileDocumentManager.getInstance().getDocument(file)?.let(FileDocumentManager.getInstance()::saveDocument)
        if (!RunnersBarShell.isAvailable) return
        RunnersBarFile.read(project.basePath)?.let { RunnersBarFile.sync(project, it) }
        val settings = RunnersBarService.getInstance(project).importedSettings(RunnersBarFile.key(group, label))
        if (settings == null) {
            Messages.showWarningDialog(project, RunnersBarBundle.message("file.gutter.missing", label), RunnersBarBundle.message("file.gutter.title"))
            return
        }
        RunnersBarExecution.execute(settings, debug = false)
    }
}

private fun JsonObject.string(name: String): String? =
    (findProperty(name)?.value as? JsonStringLiteral)?.value?.takeIf { it.isNotBlank() }
