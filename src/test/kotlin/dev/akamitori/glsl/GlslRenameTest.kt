package dev.akamitori.glsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlslRenameTest {
    private val shader = """
        struct Material { float shininess; };
        layout(binding = 0) uniform Lights { vec4 color; } lights;
        uniform Globals { float time; };
        uniform vec3 n;

        float lambert(vec3 n, vec3 l, Material m) {
            return max(dot(n, l), 0.0) * m.shininess;
        }

        void main() {
            float shininess = 2.0;
            float x = lambert(n, n, Material(shininess)) + lights.color.r + time;
        }
    """.trimIndent()

    private fun at(word: String, n: Int = 0) =
        Regex("\\b${Regex.escape(word)}\\b").findAll(shader).elementAt(n).range.first

    /** Text of each renamed occurrence, with its index among all whole-word occurrences of the name. */
    private fun hits(name: String, scope: RenameScope, def_offset: Int?): List<Int> {
        val all = Regex("\\b${Regex.escape(name)}\\b").findAll(shader).map { it.range.first }.toList()
        return rename_ranges(shader, name, scope, def_offset).map { r ->
            assertEquals(name, shader.substring(r.startOffset, r.endOffset))
            all.indexOf(r.startOffset)
        }
    }

    @Test fun `scope follows the declaration`() {
        assertTrue(rename_scope(shader, at("l", 0)) is RenameScope.Local)  // parameter
        assertEquals(RenameScope.Global, rename_scope(shader, at("lambert", 0)))  // function
        assertEquals(RenameScope.Global, rename_scope(shader, at("Material", 0)))  // struct name
        assertEquals(RenameScope.Field, rename_scope(shader, at("shininess", 0)))  // struct member
        assertEquals(RenameScope.Field, rename_scope(shader, at("color", 0)))  // member of a named block
        assertEquals(RenameScope.Global, rename_scope(shader, at("time", 0)))  // member of an unnamed block
        assertEquals(RenameScope.Global, rename_scope(shader, at("n", 0)))  // global uniform
    }

    @Test fun `parameter rename stays inside its function`() {
        val scope = rename_scope(shader, at("n", 1))  // lambert's parameter n
        // occurrences of n: 0 = uniform, 1 = param, 2 = in dot(), 3 and 4 = in main()
        assertEquals(listOf(1, 2), hits("n", scope, at("n", 1)))
    }

    @Test fun `global rename skips functions whose parameter shadows it`() {
        assertEquals(listOf(0, 3, 4), hits("n", RenameScope.Global, at("n", 0)))
    }

    @Test fun `function rename hits declaration and calls`() {
        assertEquals(listOf(0, 1), hits("lambert", RenameScope.Global, at("lambert", 0)))
    }

    @Test fun `field rename hits declaration and member accesses but not a same-named local`() {
        // shininess: 0 = struct member, 1 = m.shininess, 2 and 3 = the local in main()
        assertEquals(listOf(0, 1), hits("shininess", RenameScope.Field, at("shininess", 0)))
    }

    @Test fun `field rename in another file has no declaration offset`() {
        assertEquals(listOf(1), hits("shininess", RenameScope.Field, null))
    }

    @Test fun `global rename skips functions where a local shadows it`() {
        val text = """
            uniform float exposure;
            float tonemap(float c) { return c * exposure; }
            void main() {
                float exposure = 2.0;
                Material exposure2;
                float y = exposure * 3.0;
            }
        """.trimIndent()
        val all = Regex("\\bexposure\\b").findAll(text).map { it.range.first }.toList()
        val hits = rename_ranges(text, "exposure", RenameScope.Global, all[0]).map { all.indexOf(it.startOffset) }
        assertEquals(listOf(0, 1), hits)  // the uniform and its use in tonemap, not main's local
    }
}
