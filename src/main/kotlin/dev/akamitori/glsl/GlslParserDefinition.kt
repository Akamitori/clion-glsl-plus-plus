package dev.akamitori.glsl

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * Flat "parser": one root node over all tokens. No grammar, no PSI tree.
 * Structure and semantics come from glsl_analyzer over LSP.
 */
class GlslParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?) = GlslLexer()

    override fun createParser(project: Project?) = PsiParser { root, builder ->
        val marker = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        marker.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType() = FILE
    override fun getCommentTokens(): TokenSet = GlslTokens.COMMENTS
    override fun getStringLiteralElements(): TokenSet = GlslTokens.STRINGS
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = GlslFile(viewProvider)

    companion object {
        @JvmField val FILE = IFileElementType(GlslLanguage)
    }
}
