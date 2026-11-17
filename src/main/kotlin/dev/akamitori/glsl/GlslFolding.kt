package dev.akamitori.glsl

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/** Folds multi-line `{ ... }` blocks (functions, structs, interface blocks, inner blocks) and block comments. */
class GlslFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        // The PSI is flat, so every region hangs off the file node; ranges come from the lexer.
        val node = root.node
        return fold_regions(document.charsSequence)
            .map { (range, placeholder) -> FoldingDescriptor(node, range, null, placeholder) }
            .toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode) = "..."
    override fun isCollapsedByDefault(node: ASTNode) = false
}

/** (range, placeholder) for every foldable region, in no particular order. Pure (unit-tested). */
internal fun fold_regions(text: CharSequence): List<Pair<TextRange, String>> {
    val out = ArrayList<Pair<TextRange, String>>()
    fun multi_line(from: Int, to: Int) = (from until to).any { text[it] == '\n' }
    val opens = ArrayDeque<Int>()
    val lexer = GlslLexer()
    lexer.start(text, 0, text.length, 0)
    while (true) {
        val type = lexer.tokenType ?: break
        when (type) {
            GlslTokens.LBRACE -> opens.addLast(lexer.tokenStart)
            GlslTokens.RBRACE -> opens.removeLastOrNull()?.let { open ->
                if (multi_line(open, lexer.tokenEnd)) out.add(TextRange(open, lexer.tokenEnd) to "{...}")
            }
            GlslTokens.BLOCK_COMMENT -> if (multi_line(lexer.tokenStart, lexer.tokenEnd)) {
                out.add(TextRange(lexer.tokenStart, lexer.tokenEnd) to "/*...*/")
            }
        }
        lexer.advance()
    }
    return out
}
