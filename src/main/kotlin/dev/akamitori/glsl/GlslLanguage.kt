package dev.akamitori.glsl

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.FileViewProvider
import javax.swing.Icon

/**
 * The ID must not be "GLSL" (glsl4idea, plugin id `GLSL`) or "Glsl" (rmordechay's, plugin id `OpenGL-Plugin`):
 * language IDs are global, and a duplicate throws ImplementationConflictException in our file type's initializer,
 * which breaks the whole plugin. Users still see "GLSL" through [getDisplayName].
 */
const val GLSL_LANGUAGE_ID = "dev.akamitori.glsl"

object GlslLanguage : Language(GLSL_LANGUAGE_ID) {
    const val ID = GLSL_LANGUAGE_ID
    override fun getDisplayName() = "GLSL"
    private fun readResolve(): Any = GlslLanguage
}

object GlslIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/glsl.svg", GlslIcons::class.java)
}

/** Shader file extensions we claim; must match `extensions=` of the fileType in plugin.xml (checked by a test). */
val GLSL_EXTENSIONS = listOf(
    "glsl", "vert", "frag", "geom", "comp", "tesc", "tese", "mesh", "task",
    "rgen", "rint", "rahit", "rchit", "rmiss", "rcall", "vsh", "fsh",
)

/** The name is a persistent, global ID like the language ID, so it isn't "GLSL" either (that throws "Trying to override already registered file type"). */
object GlslFileType : LanguageFileType(GlslLanguage) {
    override fun getName() = GLSL_LANGUAGE_ID
    override fun getDisplayName() = "GLSL"
    override fun getDescription() = "GLSL shader"
    override fun getDefaultExtension() = "glsl"
    override fun getIcon() = GlslIcons.FILE
}

class GlslFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, GlslLanguage) {
    override fun getFileType() = GlslFileType
    override fun toString() = "GLSL File"
}
