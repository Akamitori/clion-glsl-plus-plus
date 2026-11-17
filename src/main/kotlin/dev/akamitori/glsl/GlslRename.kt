package dev.akamitori.glsl

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.tree.IElementType

/**
 * Rename for GLSL, bound to Shift+F6 and enabled only in GLSL files.
 *
 * glsl_analyzer has no rename or references support, and CLion Nova sends the stock Rename to its
 * C++ backend, so this is our own token-level rename. glsl_analyzer is still used to find the
 * declaration, which decides the scope:
 *  - declared inside a function (parameter or local): only inside that function;
 *  - a struct member, or a member of a named interface block: the declaration plus every `.name`;
 *  - top level (function, global, struct, macro): every GLSL file in the project, skipping
 *    functions that have a parameter of the same name.
 *
 * Not handled: a local variable that shadows a global of the same name in another function
 * is renamed along with the global. Built-ins (no declaration in your code) can't be renamed.
 */
class GlslRenameAction : AnAction(), DumbAware, GlslEditorAction {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.getData(CommonDataKeys.EDITOR) != null && e.getData(CommonDataKeys.PSI_FILE) is GlslFile
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val found = find_symbol_occurrences(project, editor, file, "rename") ?: return
        val name = found.name
        val plan = found.ranges

        val count = plan.values.sumOf { it.size }
        val where = if (plan.size == 1) "" else " in ${plan.size} files"
        val new_name = Messages.showInputDialog(
            project, "Rename '$name' ($count occurrence${if (count == 1) "" else "s"}$where) to:",
            "Rename", null, name, IdentifierValidator,
        ) ?: return
        if (new_name == name) return

        val docs = plan.mapKeys { (vf, _) -> FileDocumentManager.getInstance().getDocument(vf) }.filterKeys { it != null }
        WriteCommandAction.writeCommandAction(project).withName("Rename '$name' to '$new_name'").run<Exception> {
            for ((doc, ranges) in docs) {
                // Back to front so earlier offsets stay valid.
                for (r in ranges.sortedByDescending { it.startOffset }) doc!!.replaceString(r.startOffset, r.endOffset, new_name)
            }
        }
    }
}

/** A symbol under the caret and every occurrence of it, per file. Shared by rename and Find Usages. */
internal class SymbolOccurrences(val name: String, val ranges: Map<VirtualFile, List<TextRange>>)

/**
 * Finds the symbol under the caret and its occurrences (same scoping as rename), with a progress
 * dialog. Call on the EDT. Shows a hint and returns null when there's nothing of ours to act on;
 * `verb` goes in that hint, e.g. "rename" or "find usages of".
 */
internal fun find_symbol_occurrences(
    project: Project, editor: com.intellij.openapi.editor.Editor, file: VirtualFile, verb: String,
): SymbolOccurrences? {
    val toks = lex(editor.document.immutableCharSequence)
    val at = token_index_at(toks, editor.caretModel.offset)
    if (at == null || toks[at].type != GlslTokens.IDENT) {
        show_hint(editor, "Can only $verb your own functions, variables, structs and macros")
        return null
    }
    val name = toks[at].text
    val position = lsp_position(editor, toks[at].start)
    val server = glsl_server(project)

    val plan = ProgressManager.getInstance().runProcessWithProgressSynchronously<Map<VirtualFile, List<TextRange>>?, Exception>(
        {
            val def = server?.let { request_definition(it, file, position).firstOrNull() }
            ReadAction.computeBlocking<Map<VirtualFile, List<TextRange>>?, Exception> {
                val def_file = def?.let { server.descriptor.findFileByUri(it.first) }
                val def_doc = def_file?.let { FileDocumentManager.getInstance().getDocument(it) }
                if (def_file != null && def_doc != null) {
                    val def_offset = offset_of(def_doc, def.second.start.line, def.second.start.character)
                    plan_rename(project, name, def_file, def_doc, def_offset)
                } else if (Regex("^\\s*#\\s*define\\s+${Regex.escape(name)}\\b", RegexOption.MULTILINE)
                        .containsMatchIn(editor.document.immutableCharSequence)) {
                    plan_global(project, name, file)  // macros: glsl_analyzer doesn't resolve them
                } else {
                    null
                }
            }
        },
        "Finding usages of '$name'", true, project,
    )
    if (plan == null) {
        show_hint(editor, "Cannot $verb '$name': no declaration found (built-in?)")
        return null
    }
    return SymbolOccurrences(name, plan)
}

/** A valid GLSL identifier that the lexer sees as a plain identifier (so not a keyword, type, builtin or gl_*). */
private object IdentifierValidator : InputValidator {
    override fun checkInput(input: String): Boolean {
        if (!Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(input) || input.startsWith("gl_") || "__" in input) return false
        val toks = lex(input)
        return toks.size == 1 && toks[0].type == GlslTokens.IDENT
    }
    override fun canClose(input: String) = checkInput(input)
}

// ---- scope ----

internal class LexTok(val type: IElementType, val text: String, val start: Int) {
    val end get() = start + text.length
}

/** Significant tokens only (no whitespace or comments). */
internal fun lex(text: CharSequence): List<LexTok> {
    val out = ArrayList<LexTok>()
    val lexer = GlslLexer()
    lexer.start(text, 0, text.length, 0)
    while (lexer.tokenType != null) {
        val type = lexer.tokenType!!
        if (type != TokenType.WHITE_SPACE && type !in GlslTokens.COMMENTS) {
            out.add(LexTok(type, text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString(), lexer.tokenStart))
        }
        lexer.advance()
    }
    return out
}

private fun token_index_at(toks: List<LexTok>, offset: Int): Int? {
    // Caret right after an identifier (e.g. at its end) still counts.
    return toks.indexOfFirst { offset >= it.start && offset < it.end }.takeIf { it >= 0 }
        ?: toks.indexOfFirst { offset == it.end && it.type == GlslTokens.IDENT }.takeIf { it >= 0 }
}

private fun offset_of(doc: Document, line: Int, character: Int): Int {
    if (line >= doc.lineCount) return doc.textLength
    return (doc.getLineStartOffset(line) + character).coerceAtMost(doc.getLineEndOffset(line))
}

/** A brace block at file level: a function (with its header and parameter names), a struct, or an interface block. */
private class Block(val kind: Kind, val from: Int, val to: Int, val params: Set<String>, val has_instance: Boolean) {
    enum class Kind { FUNCTION, STRUCT, INTERFACE }
}

private fun top_level_blocks(toks: List<LexTok>): List<Block> {
    val out = ArrayList<Block>()
    var depth = 0
    var stmt_start = 0
    var open = -1
    for ((i, t) in toks.withIndex()) {
        when (t.type) {
            GlslTokens.LBRACE -> { if (depth == 0) open = i; depth++ }
            GlslTokens.RBRACE -> {
                depth--
                if (depth == 0 && open >= 0) {
                    out.add(make_block(toks, stmt_start, open, i))
                    stmt_start = i + 1
                    open = -1
                }
            }
            GlslTokens.SEMICOLON -> if (depth == 0) stmt_start = i + 1
            else -> {}
        }
    }
    return out
}

private fun make_block(toks: List<LexTok>, stmt_start: Int, open: Int, close: Int): Block {
    if (toks.getOrNull(open - 1)?.type == GlslTokens.RPAREN) {
        // Function: walk back to the matching '(' of the parameter list.
        var depth = 0
        var lp = open - 1
        while (lp >= stmt_start) {
            if (toks[lp].type == GlslTokens.RPAREN) depth++
            if (toks[lp].type == GlslTokens.LPAREN && --depth == 0) break
            lp--
        }
        val params = HashSet<String>()
        var last: String? = null
        for (j in lp + 1 until open) {
            val t = toks[j]
            if (t.type == GlslTokens.IDENT) last = t.text
            if (t.type == GlslTokens.COMMA || t.type == GlslTokens.RPAREN) { last?.let(params::add); last = null }
        }
        return Block(Block.Kind.FUNCTION, toks[lp.coerceAtLeast(0)].start, toks[close].end, params, false)
    }
    val is_struct = (stmt_start until open).any { toks[it].type == GlslTokens.KEYWORD && toks[it].text == "struct" }
    val has_instance = toks.getOrNull(close + 1)?.type == GlslTokens.IDENT
    return Block(if (is_struct) Block.Kind.STRUCT else Block.Kind.INTERFACE, toks[open].start, toks[close].end, emptySet(), has_instance)
}

private fun plan_rename(project: Project, name: String, def_file: VirtualFile, def_doc: Document, def_offset: Int): Map<VirtualFile, List<TextRange>> =
    when (val scope = rename_scope(def_doc.immutableCharSequence, def_offset)) {
        is RenameScope.Local -> mapOf(def_file to rename_ranges(def_doc.immutableCharSequence, name, scope, def_offset))
        else -> plan_across_project(project, name, scope, def_file, def_offset)
    }

private fun plan_global(project: Project, name: String, def_file: VirtualFile) =
    plan_across_project(project, name, RenameScope.Global, def_file, null)

private fun plan_across_project(
    project: Project, name: String, scope: RenameScope, def_file: VirtualFile, def_offset: Int?,
): Map<VirtualFile, List<TextRange>> {
    val out = HashMap<VirtualFile, List<TextRange>>()
    val files = FileTypeIndex.getFiles(GlslFileType, GlobalSearchScope.projectScope(project)) + def_file
    for (vf in files.toSet()) {
        ProgressManager.checkCanceled()
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: continue
        val ranges = rename_ranges(doc.immutableCharSequence, name, scope, def_offset.takeIf { vf == def_file })
        if (ranges.isNotEmpty()) out[vf] = ranges
    }
    return out
}

// ---- pure scoping (unit-tested in GlslRenameTest) ----

/** Where a renamed symbol's occurrences live, decided by where it is declared. */
sealed interface RenameScope {
    /** Parameter or local: only inside this function (header + body) of the declaring file. */
    data class Local(val from: Int, val to: Int) : RenameScope
    /** Struct member or member of a named interface block: the declaration plus every `.name`. */
    data object Field : RenameScope
    /** Function, global, struct, block or macro: every plain use in every file. */
    data object Global : RenameScope
}

/** Scope for a symbol declared at `def_offset` in `text`. */
internal fun rename_scope(text: CharSequence, def_offset: Int): RenameScope {
    val block = top_level_blocks(lex(text)).firstOrNull { def_offset >= it.from && def_offset < it.to }
    return when {
        block == null -> RenameScope.Global
        block.kind == Block.Kind.FUNCTION -> RenameScope.Local(block.from, block.to)
        block.kind == Block.Kind.STRUCT || block.has_instance -> RenameScope.Field
        else -> RenameScope.Global  // unnamed interface block: members are globals
    }
}

/**
 * Occurrences of `name` in one file's `text` for `scope`. `def_offset` is the declaration's offset
 * if it is in this file (needed for Field, where the declaration itself has no leading dot).
 */
internal fun rename_ranges(text: CharSequence, name: String, scope: RenameScope, def_offset: Int?): List<TextRange> {
    val toks = lex(text)
    fun is_ident(i: Int) = toks[i].type == GlslTokens.IDENT && toks[i].text == name
    fun after_dot(i: Int) = toks.getOrNull(i - 1)?.type == GlslTokens.DOT
    val hits = when (scope) {
        is RenameScope.Local -> toks.indices.filter { i ->
            is_ident(i) && !after_dot(i) && toks[i].start >= scope.from && toks[i].end <= scope.to
        }
        RenameScope.Field -> toks.indices.filter { i -> is_ident(i) && (after_dot(i) || toks[i].start == def_offset) }
        RenameScope.Global -> {
            // Skip functions with a parameter of the same name: inside them the name means the parameter.
            val shadowing = top_level_blocks(toks).filter { it.kind == Block.Kind.FUNCTION && name in it.params }
            toks.indices.filter { i ->
                is_ident(i) && !after_dot(i) && shadowing.none { toks[i].start >= it.from && toks[i].end <= it.to }
            }
        }
    }
    return hits.map { TextRange(toks[it].start, toks[it].end) }
}
