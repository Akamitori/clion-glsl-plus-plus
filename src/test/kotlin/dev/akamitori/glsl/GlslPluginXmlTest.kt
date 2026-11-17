package dev.akamitori.glsl

import com.intellij.lang.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coexistence with other GLSL plugins: found on Windows, where glsl4idea was installed and broke our file type. */
class GlslPluginXmlTest {
    private val plugin_xml = GlslPluginXmlTest::class.java.getResource("/META-INF/plugin.xml")!!.readText()

    private object Glsl4IdeaLanguage : Language("GLSL")
    private object RmordechayLanguage : Language("Glsl")

    @Test
    fun `language id differs from other GLSL plugins`() {
        assertFalse(GlslLanguage.ID.equals("GLSL", ignoreCase = true))
        assertEquals("GLSL", GlslLanguage.displayName)
    }

    @Test
    fun `other plugins' languages can be registered next to ours`() {
        // Each of these threw ImplementationConflictException when our ID was "GLSL".
        assertEquals("GLSL", Glsl4IdeaLanguage.id)
        assertEquals("Glsl", RmordechayLanguage.id)
        assertEquals(GlslLanguage, Language.findLanguageByID(GlslLanguage.ID))
    }

    @Test
    fun `every extension in plugin xml uses our language id`() {
        val ids = Regex("""language="([^"]*)"""").findAll(plugin_xml).map { it.groupValues[1] }.toSet()
        assertEquals(setOf(GlslLanguage.ID), ids)
    }

    @Test
    fun `plugin xml declares the other GLSL plugins incompatible`() {
        assertTrue(plugin_xml.contains("<incompatible-with>GLSL</incompatible-with>"))
        assertTrue(plugin_xml.contains("<incompatible-with>OpenGL-Plugin</incompatible-with>"))
    }
}
