package dev.akamitori.glsl

import com.intellij.codeInsight.navigation.CtrlMouseAction
import com.intellij.codeInsight.navigation.CtrlMouseData
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.TokenSet
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.ui.awt.RelativePoint
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * Go to declaration for GLSL, bound to Ctrl+B / Ctrl+click and enabled only in GLSL files.
 *
 * CLion Nova (Radler) takes over the stock GotoDeclaration action and, further down, the platform's
 * navigation code, and sends them to the ReSharper backend, which can't resolve GLSL ("Cannot find
 * declaration to go to"). So this action asks glsl_analyzer directly with textDocument/definition and
 * opens the result itself. No platform navigation code is involved, so there is nothing to reroute.
 */
class GlslGotoDeclarationAction : AnAction(), DumbAware, CtrlMouseAction, GlslEditorAction {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /**
     * Ctrl+hover underline. Called off the EDT as the mouse moves; underline the token only when
     * glsl_analyzer actually has a definition for it, so built-ins like vec4 stay plain.
     */
    override fun getCtrlMouseData(editor: Editor, file: PsiFile, offset: Int): CtrlMouseData? {
        if (file !is GlslFile) return null
        val token = file.findElementAt(offset) ?: return null
        if (token.node.elementType !in NAVIGABLE_TOKENS) return null
        val vfile = file.virtualFile ?: return null
        val server = glsl_server(file.project) ?: return null
        val targets = request_definition(server, vfile, lsp_position(editor, offset), timeout_ms = 500)
        if (targets.isEmpty()) return null
        return CtrlMouseData(listOf(token.textRange), true, null, null)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.getData(CommonDataKeys.EDITOR) != null && e.getData(CommonDataKeys.PSI_FILE) is GlslFile
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val server = glsl_server(project) ?: return
        val offset = (e.inputEvent as? MouseEvent)?.let { mouse_offset(editor, it) } ?: editor.caretModel.offset
        val position = ReadAction.compute<Position, Throwable> { lsp_position(editor, offset) }

        ApplicationManager.getApplication().executeOnPooledThread {
            val targets = request_definition(server, file, position)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (targets.isEmpty()) {
                    show_hint(editor, "Cannot find declaration to go to")
                } else {
                    // glsl_analyzer returns at most one location in practice; take the first.
                    navigate(project, server, targets.first())
                }
            }
        }
    }
}

/** Our editor actions that share a shortcut with a stock action CLion Nova reroutes to its C++ backend. */
interface GlslEditorAction

/**
 * When a shortcut (Ctrl+B, Ctrl+click, Shift+F6) is bound to several actions, try ours first.
 * Each one disables itself outside GLSL files, so other languages fall through to the stock action.
 */
class GlslActionPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> =
        actions.filter { it is GlslEditorAction }
}

/** Tokens worth asking the server about; keywords, numbers, punctuation never have a definition. */
internal val NAVIGABLE_TOKENS = TokenSet.create(GlslTokens.IDENT, GlslTokens.BUILTIN_FUNC, GlslTokens.BUILTIN_VAR, GlslTokens.TYPE)

internal fun glsl_server(project: Project): LspServer? =
    LspServerManager.getInstance(project)
        .getServersForProvider(GlslLspServerSupportProvider::class.java)
        .firstOrNull()

private fun mouse_offset(editor: Editor, e: MouseEvent): Int {
    val point = SwingUtilities.convertPoint(e.component, e.point, editor.contentComponent)
    return editor.logicalPositionToOffset(editor.xyToLogicalPosition(point))
}

/** LSP positions are 0-based line + UTF-16 column, which is exactly a Java char offset within the line. */
internal fun lsp_position(editor: Editor, offset: Int): Position {
    val doc = editor.document
    val line = doc.getLineNumber(offset)
    return Position(line, offset - doc.getLineStartOffset(line))
}

/** Returns (uri, range) pairs; handles both Location[] and LocationLink[] responses. */
internal fun request_definition(
    server: LspServer,
    file: VirtualFile,
    position: Position,
    timeout_ms: Int = 5000,
): List<Pair<String, Range>> {
    val params = DefinitionParams(server.getDocumentIdentifier(file), position)
    val result = server.sendRequestSync(timeout_ms) { it.textDocumentService.definition(params) } ?: return emptyList()
    return if (result.isLeft) {
        result.left.map { loc: Location -> loc.uri to loc.range }
    } else {
        result.right.map { link -> link.targetUri to link.targetSelectionRange }
    }
}

private fun navigate(project: Project, server: LspServer, target: Pair<String, Range>) {
    val (uri, range) = target
    val file = server.descriptor.findFileByUri(uri) ?: return
    OpenFileDescriptor(project, file, range.start.line, range.start.character).navigate(true)
}

internal fun show_hint(editor: Editor, text: String) {
    val point = editor.visualPositionToXY(editor.caretModel.visualPosition)
    JBPopupFactory.getInstance()
        .createHtmlTextBalloonBuilder(text, com.intellij.openapi.ui.MessageType.INFO, null)
        .setFadeoutTime(2500)
        .createBalloon()
        .show(RelativePoint(editor.contentComponent, point), com.intellij.openapi.ui.popup.Balloon.Position.above)
}
