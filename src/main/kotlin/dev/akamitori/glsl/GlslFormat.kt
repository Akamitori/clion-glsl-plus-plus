package dev.akamitori.glsl

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.TextEdit

/**
 * Reformat Code for GLSL, bound to Ctrl+Alt+L and enabled only in GLSL files.
 *
 * CLion Nova sends the stock Reformat Code to its C++ backend, so the request never reached
 * glsl_analyzer. This asks glsl_analyzer for textDocument/formatting directly and applies the edits.
 * Indentation follows the editor's GLSL code style settings (tab size, tabs vs spaces).
 */
class GlslReformatAction : AnAction(), DumbAware, GlslEditorAction {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.getData(CommonDataKeys.EDITOR) != null && e.getData(CommonDataKeys.PSI_FILE) is GlslFile
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val server = glsl_server(project) ?: run { show_hint(editor, "glsl_analyzer is not running"); return }
        val doc = editor.document
        val stamp = doc.modificationStamp
        val options = FormattingOptions(editor.settings.getTabSize(project), !editor.settings.isUseTabCharacter(project))
        val params = DocumentFormattingParams(server.getDocumentIdentifier(file), options)

        ApplicationManager.getApplication().executeOnPooledThread {
            val edits = server.sendRequestSync(5000) { it.textDocumentService.formatting(params) }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                when {
                    edits == null -> show_hint(editor, "glsl_analyzer could not format this file (syntax error?)")
                    doc.modificationStamp != stamp -> {}  // edited while formatting; the edits no longer apply
                    edits.isNotEmpty() -> WriteCommandAction.writeCommandAction(project).withName("Reformat GLSL").run<Exception> {
                        apply_edits(doc, edits)
                    }
                }
            }
        }
    }
}

/** Applies LSP text edits back to front, so earlier offsets stay valid. */
private fun apply_edits(doc: Document, edits: List<TextEdit>) {
    fun offset(line: Int, character: Int): Int {
        if (line >= doc.lineCount) return doc.textLength
        return (doc.getLineStartOffset(line) + character).coerceAtMost(doc.getLineEndOffset(line))
    }
    val resolved = edits.map { Triple(offset(it.range.start.line, it.range.start.character), offset(it.range.end.line, it.range.end.character), it.newText) }
    for ((start, end, text) in resolved.sortedByDescending { it.first }) {
        doc.replaceString(start, end, text.replace("\r\n", "\n"))
    }
}
