package dev.akamitori.glsl

import com.intellij.lang.ASTNode
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * Context-aware identifier colors on top of the lexer: functions, parameters, globals, structs,
 * fields, swizzles, macros and layout qualifiers.
 *
 * This is a heuristic token scan, not a parser. It only has to be right for ordinary shader code,
 * and when it guesses wrong the worst case is a plain identifier. glsl_analyzer sends no semantic
 * tokens, so this is the only source of these colors.
 *
 * Runs once per file (the file is the only composite PSI node; everything else is a flat leaf).
 */
class GlslSemanticAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is GlslFile) return
        val tokens = collect_tokens(element.node)
        val decls = scan_declarations(tokens)
        classify(tokens, decls) { range, key ->
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
        }
    }
}

/** A significant token: no whitespace or comments. in_pp = part of a preprocessor line. */
private class Tok(val type: IElementType, val text: String, val range: TextRange, val in_pp: Boolean)

private fun collect_tokens(file: ASTNode): List<Tok> {
    val out = ArrayList<Tok>()
    var in_pp = false
    var n = file.firstChildNode
    while (n != null) {
        val type = n.elementType
        when {
            type == TokenType.WHITE_SPACE -> if (n.textContains('\n') && !ends_with_continuation(n, out)) in_pp = false
            type in GlslTokens.COMMENTS -> if (type == GlslTokens.LINE_COMMENT) in_pp = false
            else -> {
                if (type == GlslTokens.PP_DIRECTIVE) in_pp = true
                out.add(Tok(type, n.text, n.textRange, in_pp))
            }
        }
        n = n.treeNext
    }
    return out
}

/** A `\` right before the newline continues a preprocessor line (multi-line #define). */
private fun ends_with_continuation(ws: ASTNode, prev: List<Tok>): Boolean {
    val last = prev.lastOrNull() ?: return false
    return last.text == "\\" && last.range.endOffset == ws.startOffset
}

private class Decls {
    val structs = HashSet<String>()
    val macros = HashSet<String>()
    val globals = HashSet<String>()
    /** Indices of function name tokens at their declarations. */
    val functions = HashSet<Int>()
    /** Parameter scopes in token order: (first, last, names); header only for a prototype, header + body for a definition. */
    val scopes = ArrayList<Triple<Int, Int, Set<String>>>()
}

private val SWIZZLE = Regex("^([xyzw]{1,4}|[rgba]{1,4}|[stpq]{1,4})$")

private fun is_ident(t: Tok) = t.type == GlslTokens.IDENT

private fun is_type_like(t: Tok, decls: Decls) = t.type == GlslTokens.TYPE || (is_ident(t) && t.text in decls.structs)

/**
 * Pass 1: walk top-level statements and record what they declare.
 * Struct names are gathered first, since later declarations use them as types.
 */
private fun scan_declarations(toks: List<Tok>): Decls {
    val d = Decls()

    // #define NAME, and struct NAME, anywhere.
    for (i in toks.indices) {
        val t = toks[i]
        if (t.type == GlslTokens.PP_DIRECTIVE && t.text.removePrefix("#").trim() == "define") {
            toks.getOrNull(i + 1)?.takeIf { is_ident(it) && it.in_pp }?.let { d.macros.add(it.text) }
        }
        if (t.type == GlslTokens.KEYWORD && t.text == "struct") {
            toks.getOrNull(i + 1)?.takeIf(::is_ident)?.let { d.structs.add(it.text) }
        }
    }

    // Top-level statements: everything at brace depth 0 up to ';' or '{'.
    var i = 0
    while (i < toks.size) {
        if (toks[i].in_pp) { i++; continue }
        val start = i
        var paren = 0
        while (i < toks.size) {
            val t = toks[i]
            if (t.in_pp) break
            if (t.type == GlslTokens.LPAREN) paren++
            if (t.type == GlslTokens.RPAREN) paren--
            if (paren == 0 && (t.type == GlslTokens.SEMICOLON || t.type == GlslTokens.LBRACE)) break
            i++
        }
        if (i >= toks.size || toks[i].in_pp) continue
        val end = i  // ';' or '{'
        val stmt = start until end

        if (toks[end].type == GlslTokens.LBRACE) {
            val close = matching_brace(toks, end)
            val fn = function_header(toks, stmt, d)
            when {
                fn != null -> {
                    d.functions.add(fn.name)
                    d.scopes.add(Triple(fn.lparen, close, fn.params))
                }
                // Interface block: `uniform Name { members } instance;`  /  struct definition
                else -> {
                    stmt.lastOrNull()?.let { toks[it] }?.takeIf(::is_ident)?.let { d.structs.add(it.text) }
                    if (stmt.any { toks[it].type == GlslTokens.KEYWORD && toks[it].text != "struct" }) {
                        // Block members are globals when the block has no instance name.
                        member_names(toks, end + 1, close, d).forEach(d.globals::add)
                        toks.getOrNull(close + 1)?.takeIf(::is_ident)?.let { d.globals.add(it.text) }
                    }
                }
            }
            i = close + 1
        } else {
            // A prototype `float f(vec3 n);` declares nothing we colour beyond the name itself.
            val fn = function_header(toks, stmt, d)
            if (fn != null) {
                d.functions.add(fn.name)
                d.scopes.add(Triple(fn.lparen, fn.rparen, fn.params))
            } else declarator_names(toks, stmt, d).forEach { d.globals.add(toks[it].text) }
            i = end + 1
        }
    }
    return d
}

private fun matching_brace(toks: List<Tok>, open: Int): Int {
    var depth = 0
    for (j in open until toks.size) {
        if (toks[j].type == GlslTokens.LBRACE) depth++
        if (toks[j].type == GlslTokens.RBRACE && --depth == 0) return j
    }
    return toks.size - 1
}

private class FnHeader(val name: Int, val lparen: Int, val rparen: Int, val params: Set<String>)

/** `[qualifiers] type name ( params )`, or null if the statement isn't a function header. */
private fun function_header(toks: List<Tok>, stmt: IntRange, d: Decls): FnHeader? {
    val lp = stmt.firstOrNull { toks[it].type == GlslTokens.LPAREN } ?: return null
    val name = lp - 1
    if (name < stmt.first + 1 || !is_ident(toks[name])) return null
    val ret = toks[name - 1]
    if (!is_type_like(ret, d) && ret.type != GlslTokens.RBRACKET) return null
    val rp = (lp until stmt.last + 1).lastOrNull { toks[it].type == GlslTokens.RPAREN } ?: return null
    if (rp != stmt.last) return null  // something after ')' -> not a plain function header
    // Each parameter's name is its last identifier before ',' / ')' / '['.
    val params = HashSet<String>()
    var last_ident: String? = null
    var bracket = 0
    for (j in lp + 1..rp) {
        val t = toks[j]
        when {
            t.type == GlslTokens.LBRACKET -> bracket++
            t.type == GlslTokens.RBRACKET -> bracket--
            bracket == 0 && (t.type == GlslTokens.COMMA || t.type == GlslTokens.RPAREN) -> {
                last_ident?.let(params::add); last_ident = null
            }
            bracket == 0 && is_ident(t) && t.text !in d.structs -> last_ident = t.text
        }
    }
    return FnHeader(name, lp, rp, params)
}

/** Names declared by a statement like `uniform vec3 a, b[2] = ..., c;` (indices into toks). */
private fun declarator_names(toks: List<Tok>, range: IntRange, d: Decls): List<Int> {
    val out = ArrayList<Int>()
    var seen_type = false
    var expect_name = false
    var depth = 0  // () and [] nesting, so initializers and array sizes are skipped
    var in_init = false
    for (j in range) {
        val t = toks[j]
        when (t.type) {
            GlslTokens.LPAREN, GlslTokens.LBRACKET -> depth++
            GlslTokens.RPAREN, GlslTokens.RBRACKET -> depth--
        }
        if (depth > 0) continue
        when {
            t.type == GlslTokens.COMMA -> { in_init = false; expect_name = seen_type }
            t.type == GlslTokens.OPERATOR && t.text == "=" -> in_init = true
            in_init -> {}
            !seen_type && is_type_like(t, d) -> { seen_type = true; expect_name = true }
            expect_name && is_ident(t) -> { out.add(j); expect_name = false }
        }
    }
    return out
}

/** Member names inside a block body `{ type a; type b[2]; }` between open+1 and close. */
private fun member_names(toks: List<Tok>, from: Int, close: Int, d: Decls): List<String> {
    val out = ArrayList<String>()
    var j = from
    while (j < close) {
        var k = j
        while (k < close && toks[k].type != GlslTokens.SEMICOLON) k++
        declarator_names(toks, j until k, d).forEach { out.add(toks[it].text) }
        j = k + 1
    }
    return out
}

/** Pass 2: pick a colour for each identifier. */
private fun classify(toks: List<Tok>, d: Decls, emit: (TextRange, TextAttributesKey) -> Unit) {
    var layout_depth = -1  // paren depth at which we entered layout(...), or -1
    var paren = 0
    var scope = 0  // index into d.scopes of the next/current parameter scope

    for ((i, t) in toks.withIndex()) {
        if (t.type == GlslTokens.LPAREN) {
            paren++
            if (toks.getOrNull(i - 1)?.let { it.type == GlslTokens.KEYWORD && it.text == "layout" } == true) layout_depth = paren
        }
        if (t.type == GlslTokens.RPAREN) {
            if (paren == layout_depth) layout_depth = -1
            paren--
        }
        if (!is_ident(t)) continue

        while (scope < d.scopes.size && d.scopes[scope].second < i) scope++
        val params = d.scopes.getOrNull(scope)?.takeIf { it.first <= i }?.third

        val prev = toks.getOrNull(i - 1)
        val next = toks.getOrNull(i + 1)
        val key = when {
            t.in_pp -> if (t.text in d.macros) GlslColors.MACRO else null
            prev?.type == GlslTokens.DOT -> if (SWIZZLE.matches(t.text)) GlslColors.SWIZZLE else GlslColors.FIELD
            layout_depth > 0 -> GlslColors.LAYOUT_QUALIFIER
            i in d.functions -> GlslColors.FUNCTION_DECLARATION
            t.text in d.structs -> GlslColors.STRUCT_NAME
            next?.type == GlslTokens.LPAREN -> GlslColors.FUNCTION_CALL
            t.text in d.macros -> GlslColors.MACRO
            params != null && t.text in params -> GlslColors.PARAMETER
            t.text in d.globals -> GlslColors.GLOBAL_VAR
            else -> null
        }
        if (key != null) emit(t.range, key)
    }
}
