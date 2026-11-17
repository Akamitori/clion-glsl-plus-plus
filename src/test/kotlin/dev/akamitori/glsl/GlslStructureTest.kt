package dev.akamitori.glsl

import com.intellij.openapi.util.TextRange
import org.junit.Assert.assertEquals
import org.junit.Test

class GlslStructureTest {
    private val shader = """
        #version 460 core
        #include "common/lighting.glsl"
        #define MAX_LIGHTS 8
        #define SQR(x) \
            ((x) * (x))
        precision highp float;
        layout(local_size_x = 8) in;

        layout(location = 0) in vec3 v_normal;
        uniform vec3 u_light_dir[MAX_LIGHTS], u_extra;
        const float PI = 3.14159;

        struct Material {
            float shininess;
            vec3 albedo, emissive;
        };

        layout(std140) uniform Camera { mat4 view; vec3 eye; } camera;

        /* A
           multi-line
           comment */
        float lambert(vec3 n,
                      vec3 l) {
            if (n.x > 0.0) {
                return 1.0;
            }
            return max(dot(n, l), 0.0);
        }

        float proto(vec3 p);

        void main() { }
    """.trimIndent()

    // ---- outline ----

    private fun summary(items: List<OutlineItem>, indent: String = ""): List<String> = items.flatMap { item ->
        listOf("$indent${item.kind} ${item.name}${item.detail?.let { " | $it" } ?: ""}") + summary(item.children, "$indent  ")
    }

    @Test fun `outline lists top-level declarations in order`() {
        assertEquals(
            listOf(
                "MACRO MAX_LIGHTS",
                "MACRO SQR",
                "VARIABLE v_normal | vec3 (in)",
                "VARIABLE u_light_dir | vec3 (uniform)",
                "VARIABLE u_extra | vec3 (uniform)",
                "VARIABLE PI | float (const)",
                "STRUCT Material",
                "  FIELD shininess | float",
                "  FIELD albedo | vec3",
                "  FIELD emissive | vec3",
                "BLOCK Camera | uniform, as camera",
                "  FIELD view | mat4",
                "  FIELD eye | vec3",
                "FUNCTION lambert | (vec3 n, vec3 l): float",
                "FUNCTION main | (): void",
            ),
            summary(outline(shader)),
        )
    }

    @Test fun `outline offsets point at the names`() {
        for (item in outline(shader)) {
            assertEquals(item.name, shader.substring(item.offset, item.offset + item.name.length))
        }
    }

    @Test fun `outline survives broken code`() {
        outline("")
        outline("float f(")
        outline("struct { ")
        outline("}}} ;; uniform")
    }

    // ---- folding ----

    @Test fun `folds multi-line blocks and comments only`() {
        val regions = fold_regions(shader).map { (r, p) -> shader.substring(r.startOffset, r.endOffset).lines().first() to p }.toSet()
        assertEquals(
            setOf(
                "{" to "{...}",           // struct Material
                "/* A" to "/*...*/",
                "{" to "{...}",           // lambert and its if-block (same first line text)
            ),
            regions,
        )
        val ranges = fold_regions(shader).map { it.first }
        assertEquals(4, ranges.size)  // struct, comment, lambert, if; not the one-line Camera block or main() { }
        for (r in ranges) assertEquals(true, shader.substring(r.startOffset, r.endOffset).contains('\n'))
    }

    @Test fun `unbalanced braces do not throw`() {
        fold_regions("}\n{\n{\n}")
        fold_regions("/* open\n")
    }

    // ---- #include under the caret ----

    @Test fun `include path under the caret`() {
        val text = "#version 460\n  #  include \"common/lighting.glsl\" // c\n#include <lib/a.glsl>\nfloat x;"
        val q = text.indexOf("\"common")
        val expected = TextRange(q, q + "\"common/lighting.glsl\"".length)
        assertEquals("common/lighting.glsl" to expected, include_at(text, q))           // on the opening quote
        assertEquals("common/lighting.glsl" to expected, include_at(text, q + 5))       // inside
        assertEquals("common/lighting.glsl" to expected, include_at(text, expected.endOffset))  // just after
        val a = text.indexOf("<lib")
        assertEquals("lib/a.glsl", include_at(text, a + 3)?.first)
        assertEquals(null, include_at(text, text.indexOf("include")))  // on the keyword, not the path
        assertEquals(null, include_at(text, text.indexOf("// c")))
        assertEquals(null, include_at(text, text.indexOf("float")))
        assertEquals(null, include_at(text, text.length + 1))
    }

    // ---- breadcrumbs ----

    private fun crumbs_at(marker: String, delta: Int = 0) =
        crumb_path(outline(shader), shader.indexOf(marker) + delta).map { it.name }

    @Test fun `breadcrumb path follows the caret`() {
        assertEquals(listOf("lambert"), crumbs_at("return 1.0"))            // deep in lambert's body
        assertEquals(listOf("lambert"), crumbs_at("lambert(vec3"))           // on the name itself
        assertEquals(listOf("lambert"), crumbs_at("return max", 28))        // on the closing brace
        assertEquals(listOf("Material", "albedo"), crumbs_at("albedo"))      // a struct field
        assertEquals(listOf("Material"), crumbs_at("vec3 albedo"))           // inside the struct, not on a field
        assertEquals(listOf("Camera", "eye"), crumbs_at("eye;"))             // interface block member
        assertEquals(listOf("main"), crumbs_at("main() { }", 8))
        assertEquals(emptyList<String>(), crumbs_at("precision"))           // top level
        assertEquals(emptyList<String>(), crumbs_at("/* A"))                 // between declarations
    }

    @Test fun `function and block ends cover their closing brace`() {
        val items = outline(shader)
        val lambert = items.single { it.name == "lambert" }
        assertEquals('}', shader[lambert.end - 1])
        assertEquals("lambert", shader.substring(lambert.offset, lambert.offset + 7))
        val material = items.single { it.name == "Material" }
        assertEquals('}', shader[material.end - 1])
    }
}
