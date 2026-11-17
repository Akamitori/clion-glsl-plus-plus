package dev.akamitori.glsl

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.FileViewProvider
import javax.swing.Icon

object GlslLanguage : Language("GLSL") {
    private fun readResolve(): Any = GlslLanguage
}

object GlslIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/glsl.svg", GlslIcons::class.java)
}

object GlslFileType : LanguageFileType(GlslLanguage) {
    override fun getName() = "GLSL"
    override fun getDescription() = "GLSL shader"
    override fun getDefaultExtension() = "glsl"
    override fun getIcon() = GlslIcons.FILE
}

class GlslFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, GlslLanguage) {
    override fun getFileType() = GlslFileType
    override fun toString() = "GLSL File"
}
