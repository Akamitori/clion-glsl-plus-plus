package dev.akamitori.glsl

import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class GlslCommenter : Commenter {
    override fun getLineCommentPrefix() = "//"
    override fun getBlockCommentPrefix() = "/*"
    override fun getBlockCommentSuffix() = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class GlslBraceMatcher : PairedBraceMatcher {
    override fun getPairs() = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int) = openingBraceOffset

    companion object {
        private val PAIRS = arrayOf(
            BracePair(GlslTokens.LPAREN, GlslTokens.RPAREN, false),
            BracePair(GlslTokens.LBRACE, GlslTokens.RBRACE, true),
            BracePair(GlslTokens.LBRACKET, GlslTokens.RBRACKET, false),
        )
    }
}
