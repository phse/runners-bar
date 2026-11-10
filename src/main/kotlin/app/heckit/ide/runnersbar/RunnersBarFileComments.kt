package app.heckit.ide.runnersbar

import com.intellij.json.codeinsight.JsonStandardComplianceProvider
import com.intellij.psi.PsiComment

/** Kommentare (`//`, `/* */`) sind in `.runners-bar` erlaubt - der Leser entfernt sie, der JSON-Editor soll sie nicht anmahnen. */
class RunnersBarFileComments : JsonStandardComplianceProvider() {
    override fun isCommentAllowed(comment: PsiComment): Boolean = comment.containingFile?.name == RunnersBarFile.FILE_NAME
}
