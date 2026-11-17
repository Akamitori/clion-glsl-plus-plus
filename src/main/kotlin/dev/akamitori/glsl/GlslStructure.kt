package dev.akamitori.glsl

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import javax.swing.Icon

/** Structure view (Alt+7): functions, structs and blocks with their members, globals, macros. */
class GlslStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(file: PsiFile): StructureViewBuilder? {
        if (file !is GlslFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                StructureViewModelBase(file, editor, FileElement(file))
                    .withSuitableClasses(GlslFile::class.java)
        }
    }
}

private class FileElement(private val file: PsiFile) : StructureViewTreeElement {
    override fun getValue(): Any = file
    override fun getPresentation(): ItemPresentation = Presentation(file.name, null, GlslIcons.FILE)
    override fun getChildren(): Array<StructureViewTreeElement> =
        outline(file.viewProvider.contents).map { ItemElement(file, it) }.toTypedArray()
    override fun navigate(requestFocus: Boolean) = file.navigate(requestFocus)
    override fun canNavigate() = true
    override fun canNavigateToSource() = true
}

private class ItemElement(private val file: PsiFile, private val item: OutlineItem) : StructureViewTreeElement {
    override fun getValue(): Any = item
    override fun getPresentation(): ItemPresentation = Presentation(item.name, item.detail, icon_for(item.kind))
    override fun getChildren(): Array<StructureViewTreeElement> = item.children.map { ItemElement(file, it) }.toTypedArray()
    override fun navigate(requestFocus: Boolean) {
        val vf = file.virtualFile ?: return
        OpenFileDescriptor(file.project, vf, item.offset).navigate(requestFocus)
    }
    override fun canNavigate() = file.virtualFile != null
    override fun canNavigateToSource() = canNavigate()
}

private class Presentation(private val text: String, private val location: String?, private val icon: Icon?) : ItemPresentation {
    override fun getPresentableText() = text
    override fun getLocationString() = location
    override fun getIcon(unused: Boolean) = icon
}

private fun icon_for(kind: OutlineKind): Icon = when (kind) {
    OutlineKind.FUNCTION -> AllIcons.Nodes.Function
    OutlineKind.STRUCT -> AllIcons.Nodes.Class
    OutlineKind.BLOCK -> AllIcons.Nodes.Interface
    OutlineKind.FIELD -> AllIcons.Nodes.Field
    OutlineKind.VARIABLE -> AllIcons.Nodes.Variable
    OutlineKind.MACRO -> AllIcons.Nodes.Constant
}

// ---- pure outline (unit-tested in GlslStructureTest) ----

enum class OutlineKind { FUNCTION, STRUCT, BLOCK, FIELD, VARIABLE, MACRO }

/**
 * name is what the tree shows; detail is the grey text after it; offset is the name's position (where
 * navigation goes); end is where the declaration ends: the closing brace for functions, structs and
 * blocks, the end of the name otherwise. [offset, end] is the range breadcrumbs treat as "inside" it.
 */
data class OutlineItem(
    val kind: OutlineKind,
    val name: String,
    val detail: String?,
    val offset: Int,
    val children: List<OutlineItem> = emptyList(),
    val end: Int = offset + name.length,
)

/** Declarations containing `offset`, outermost first (e.g. [Material, shininess] or [lambert]). Pure. */
internal fun crumb_path(items: List<OutlineItem>, offset: Int): List<OutlineItem> {
    val hit = items.firstOrNull { offset >= it.offset && offset <= it.end } ?: return emptyList()
    return listOf(hit) + crumb_path(hit.children, offset)
}

private val STORAGE = setOf("uniform", "in", "out", "inout", "buffer", "shared", "const", "attribute", "varying")

/** Top-level declarations in source order. Heuristic token scan, like the semantic highlighter. */
internal fun outline(text: CharSequence): List<OutlineItem> {
    val toks = lex(text)
    val out = ArrayList<OutlineItem>()
    var i = 0
    while (i < toks.size) {
        val t = toks[i]
        if (t.type == GlslTokens.PP_DIRECTIVE) {
            val eol = pp_line_end(text, t.start)
            if (t.text.removePrefix("#").trim() == "define") {
                toks.getOrNull(i + 1)?.takeIf { it.type == GlslTokens.IDENT && it.start < eol }?.let {
                    out.add(OutlineItem(OutlineKind.MACRO, it.text, null, it.start))
                }
            }
            while (i < toks.size && toks[i].start < eol) i++
            continue
        }
        // One top-level statement: up to ';' or '{' outside parentheses.
        val start = i
        var paren = 0
        while (i < toks.size) {
            val k = toks[i].type
            if (k == GlslTokens.PP_DIRECTIVE) break
            if (k == GlslTokens.LPAREN) paren++
            if (k == GlslTokens.RPAREN) paren--
            if (paren <= 0 && (k == GlslTokens.SEMICOLON || k == GlslTokens.LBRACE)) break
            i++
        }
        if (i >= toks.size || toks[i].type == GlslTokens.PP_DIRECTIVE) continue
        val end = i
        if (toks[end].type == GlslTokens.LBRACE) {
            val close = matching_close(toks, end)
            out.addAll(block_items(text, toks, start, end, close))
            i = close + 1
            // `} instance;` after a struct or block
            while (i < toks.size && toks[i].type != GlslTokens.SEMICOLON && toks[i].type != GlslTokens.PP_DIRECTIVE &&
                toks[i].type != GlslTokens.LBRACE && toks[i].type != GlslTokens.RBRACE) i++
            if (i < toks.size && toks[i].type == GlslTokens.SEMICOLON) i++
        } else {
            out.addAll(declaration_items(toks, start, end, kind = OutlineKind.VARIABLE))
            i = end + 1
        }
    }
    return out
}

/** Offset just past the end of the preprocessor line starting at `from`, following `\` continuations. */
private fun pp_line_end(text: CharSequence, from: Int): Int {
    var j = from
    while (j < text.length) {
        if (text[j] == '\n') {
            var k = j - 1
            if (k >= 0 && text[k] == '\r') k--
            if (k >= 0 && text[k] == '\\') { j++; continue }
            return j
        }
        j++
    }
    return text.length
}

private fun matching_close(toks: List<LexTok>, open: Int): Int {
    var depth = 0
    for (j in open until toks.size) {
        if (toks[j].type == GlslTokens.LBRACE) depth++
        if (toks[j].type == GlslTokens.RBRACE && --depth == 0) return j
    }
    return toks.size - 1
}

/** A `{`-statement: function definition, struct, or interface block. */
private fun block_items(text: CharSequence, toks: List<LexTok>, start: Int, open: Int, close: Int): List<OutlineItem> {
    if (toks.getOrNull(open - 1)?.type == GlslTokens.RPAREN) {
        // Function: name is the identifier before the parameter list's '('.
        var depth = 0
        var lp = open - 1
        while (lp > start) {
            if (toks[lp].type == GlslTokens.RPAREN) depth++
            if (toks[lp].type == GlslTokens.LPAREN && --depth == 0) break
            lp--
        }
        val name = toks.getOrNull(lp - 1)?.takeIf { it.type == GlslTokens.IDENT } ?: return emptyList()
        val ret = (start until lp - 1).joinToString(" ") { toks[it].text }
        val params = collapse(text.subSequence(toks[lp].start, toks[open - 1].end))
        return listOf(OutlineItem(OutlineKind.FUNCTION, name.text, "$params${if (ret.isEmpty()) "" else ": $ret"}", name.start,
            end = toks[close].end))
    }
    val is_struct = (start until open).any { toks[it].type == GlslTokens.KEYWORD && toks[it].text == "struct" }
    val name = toks.getOrNull(open - 1)?.takeIf { it.type == GlslTokens.IDENT }
    val members = ArrayList<OutlineItem>()
    var j = open + 1
    while (j < close) {
        var k = j
        while (k < close && toks[k].type != GlslTokens.SEMICOLON) k++
        members.addAll(declaration_items(toks, j, k, kind = OutlineKind.FIELD))
        j = k + 1
    }
    val instance = toks.getOrNull(close + 1)?.takeIf { it.type == GlslTokens.IDENT }
    if (is_struct) {
        return listOf(OutlineItem(OutlineKind.STRUCT, name?.text ?: "(anonymous struct)", null, (name ?: toks[open]).start, members,
            end = toks[close].end))
    }
    val qualifiers = (start until open).map { toks[it] }.filter { it.type == GlslTokens.KEYWORD && it.text in STORAGE }.joinToString(" ") { it.text }
    val detail = listOfNotNull(qualifiers.ifEmpty { null }, instance?.let { "as ${it.text}" }).joinToString(", ").ifEmpty { null }
    return listOf(OutlineItem(OutlineKind.BLOCK, name?.text ?: "(block)", detail, (name ?: toks[open]).start, members,
        end = toks[close].end))
}

/**
 * Declarators of `[qualifiers] type a, b[2] = x;` as items, e.g. `a: vec3 (uniform)`.
 * Function prototypes and statements without declarators (precision, `layout(...) in;`) give nothing.
 */
private fun declaration_items(toks: List<LexTok>, start: Int, end: Int, kind: OutlineKind): List<OutlineItem> {
    if (start >= end) return emptyList()
    if (toks[start].type == GlslTokens.KEYWORD && toks[start].text == "precision") return emptyList()
    val out = ArrayList<OutlineItem>()
    var depth = 0
    var type: String? = null
    var qualifiers = ArrayList<String>()
    var j = start
    while (j < end) {
        val t = toks[j]
        when (t.type) {
            GlslTokens.LPAREN, GlslTokens.LBRACKET -> depth++
            GlslTokens.RPAREN, GlslTokens.RBRACKET -> depth--
        }
        if (depth == 0) {
            when {
                t.type == GlslTokens.KEYWORD && t.text in STORAGE -> qualifiers.add(t.text)
                type == null && (t.type == GlslTokens.TYPE || t.type == GlslTokens.IDENT) -> {
                    // `name (` right after a type would be a prototype: no declarators.
                    type = t.text
                }
                type != null && t.type == GlslTokens.IDENT -> {
                    if (toks.getOrNull(j + 1)?.type == GlslTokens.LPAREN) return emptyList()
                    val q = qualifiers.joinToString(" ").ifEmpty { null }
                    out.add(OutlineItem(kind, t.text, listOfNotNull(type, q?.let { "($it)" }).joinToString(" "), t.start))
                    // Skip to the next top-level ',' (past array sizes and initializers).
                    var d = 0
                    while (j + 1 < end) {
                        val n = toks[j + 1].type
                        if (n == GlslTokens.LPAREN || n == GlslTokens.LBRACKET) d++
                        if (n == GlslTokens.RPAREN || n == GlslTokens.RBRACKET) d--
                        if (d == 0 && n == GlslTokens.COMMA) break
                        j++
                    }
                }
            }
        }
        j++
    }
    return out
}

private fun collapse(s: CharSequence) = s.toString().replace(Regex("\\s+"), " ").replace("( ", "(").replace(" )", ")")

// ---- breadcrumbs ----

/**
 * Breadcrumbs from the outline. The PSI is flat (every token's parent is the file), so instead of PSI
 * parents we hand out the *name token* of each enclosing declaration as its crumb, and getParent walks
 * outward through those. Example: caret in lambert's body -> crumb "lambert()".
 */
class GlslBreadcrumbsProvider : com.intellij.ui.breadcrumbs.BreadcrumbsProvider {
    override fun getLanguages(): Array<com.intellij.lang.Language> = arrayOf(GlslLanguage)

    override fun acceptElement(e: PsiElement) = item_named_by(e) != null

    override fun getElementInfo(e: PsiElement): String {
        val item = item_named_by(e) ?: return e.text
        return if (item.kind == OutlineKind.FUNCTION) "${item.name}()" else item.name
    }

    override fun getElementTooltip(e: PsiElement): String? = item_named_by(e)?.let { listOfNotNull(it.name, it.detail).joinToString(" ") }

    override fun getParent(e: PsiElement): PsiElement? {
        val file = e.containingFile as? GlslFile ?: return null
        if (e is PsiFile) return null
        val path = crumb_path(cached_outline(file), e.textRange.startOffset)
        // If e is a crumb itself, its parent is the next declaration out; otherwise the innermost one.
        val own = path.indexOfFirst { it.offset == e.textRange.startOffset }
        val parent = if (own >= 0) path.getOrNull(own - 1) else path.lastOrNull()
        return parent?.let { file.findElementAt(it.offset) } ?: file
    }

    /** The outline item whose name token is `e`, if any. */
    private fun item_named_by(e: PsiElement): OutlineItem? {
        if (e is PsiFile) return null
        val file = e.containingFile as? GlslFile ?: return null
        val start = e.textRange.startOffset
        return crumb_path(cached_outline(file), start).firstOrNull { it.offset == start }
    }
}

/** The outline, recomputed only when the file changes. */
private fun cached_outline(file: GlslFile): List<OutlineItem> =
    com.intellij.psi.util.CachedValuesManager.getCachedValue(file) {
        com.intellij.psi.util.CachedValueProvider.Result.create(outline(file.viewProvider.contents), file)
    }
