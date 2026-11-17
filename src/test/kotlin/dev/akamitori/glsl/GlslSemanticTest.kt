package dev.akamitori.glsl

import com.intellij.openapi.editor.colors.TextAttributesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class GlslSemanticTest {
    /** Colour of the n-th (0-based) whole-word occurrence of `word`, or null if uncoloured. */
    private fun key_at(text: String, word: String, n: Int = 0): TextAttributesKey? {
        val match = Regex("\\b${Regex.escape(word)}\\b").findAll(text).elementAtOrNull(n)
            ?: error("'$word' #$n not found")
        return semantic_highlights(text)
            .firstOrNull { (range, _) -> range.startOffset == match.range.first }
            ?.second
    }

    private val shader = """
        #version 460 core
        #define MAX_LIGHTS 8
        layout(location = 0) in vec3 v_normal;
        layout(location = 0) out vec4 frag_color;
        layout(std140, binding = 1) uniform Camera { mat4 view; vec3 eye; };
        layout(binding = 2) uniform Lights { vec4 color; } lights;
        uniform vec3 u_light_dir[MAX_LIGHTS], u_extra;

        struct Material { float shininess; };

        float lambert(vec3 n, vec3 l, Material m) {
            float local_var = dot(n, l);
            return pow(max(local_var, 0.0), m.shininess);
        }

        float proto(vec3 p);

        void main() {
            vec3 n = normalize(v_normal);
            Material mat = Material(16.0);
            float lit = lambert(n, u_light_dir[0], mat) + proto(eye);
            frag_color = vec4(lights.color.rgb * lit, 1.0);
            float p = 1.0;
        }
    """.trimIndent()

    @Test fun functions() {
        assertEquals(GlslColors.FUNCTION_DECLARATION, key_at(shader, "lambert", 0))
        assertEquals(GlslColors.FUNCTION_CALL, key_at(shader, "lambert", 1))
        assertEquals(GlslColors.FUNCTION_DECLARATION, key_at(shader, "main"))
        assertEquals(GlslColors.FUNCTION_DECLARATION, key_at(shader, "proto", 0))  // prototype
        assertEquals(GlslColors.FUNCTION_CALL, key_at(shader, "proto", 1))
    }

    @Test fun `builtins are left to the lexer`() {
        assertEquals(null, key_at(shader, "normalize"))
        assertEquals(null, key_at(shader, "dot"))
    }

    @Test fun parameters() {
        assertEquals(GlslColors.PARAMETER, key_at(shader, "n", 0))  // in the header
        assertEquals(GlslColors.PARAMETER, key_at(shader, "n", 1))  // in the body
        assertEquals(GlslColors.PARAMETER, key_at(shader, "m", 1))  // m.shininess
        // `n` in main() is a local, not lambert's parameter.
        assertEquals(null, key_at(shader, "n", 2))
    }

    @Test fun `prototype parameters do not leak into later code`() {
        assertEquals(GlslColors.PARAMETER, key_at(shader, "p", 0))  // float proto(vec3 p);
        assertEquals(null, key_at(shader, "p", 1))  // float p = 1.0; in main()
    }

    @Test fun globals() {
        assertEquals(GlslColors.GLOBAL_VAR, key_at(shader, "v_normal", 0))
        assertEquals(GlslColors.GLOBAL_VAR, key_at(shader, "v_normal", 1))  // use in main()
        assertEquals(GlslColors.GLOBAL_VAR, key_at(shader, "frag_color", 1))
        assertEquals(GlslColors.GLOBAL_VAR, key_at(shader, "u_light_dir", 0))
        assertEquals(GlslColors.GLOBAL_VAR, key_at(shader, "u_extra"))  // second declarator
        assertEquals(GlslColors.GLOBAL_VAR, key_at(shader, "eye", 1))  // member of an unnamed block
        assertEquals(GlslColors.GLOBAL_VAR, key_at(shader, "lights", 1))  // block instance name
    }

    @Test fun `locals stay plain`() {
        assertEquals(null, key_at(shader, "local_var", 0))
        assertEquals(null, key_at(shader, "lit", 0))
        assertEquals(null, key_at(shader, "mat", 0))
    }

    @Test fun `structs fields and swizzles`() {
        assertEquals(GlslColors.STRUCT_NAME, key_at(shader, "Material", 0))  // struct Material
        assertEquals(GlslColors.STRUCT_NAME, key_at(shader, "Material", 1))  // as parameter type
        assertEquals(GlslColors.STRUCT_NAME, key_at(shader, "Material", 3))  // constructor call
        assertEquals(GlslColors.STRUCT_NAME, key_at(shader, "Camera"))
        assertEquals(GlslColors.FIELD, key_at(shader, "shininess", 1))
        assertEquals(GlslColors.FIELD, key_at(shader, "color", 1))  // lights.color
        assertEquals(GlslColors.SWIZZLE, key_at(shader, "rgb"))
    }

    @Test fun macros() {
        assertEquals(GlslColors.MACRO, key_at(shader, "MAX_LIGHTS", 0))
        assertEquals(GlslColors.MACRO, key_at(shader, "MAX_LIGHTS", 1))
    }

    @Test fun `layout qualifiers`() {
        assertEquals(GlslColors.LAYOUT_QUALIFIER, key_at(shader, "location"))
        assertEquals(GlslColors.LAYOUT_QUALIFIER, key_at(shader, "std140"))
        assertEquals(GlslColors.LAYOUT_QUALIFIER, key_at(shader, "binding"))
    }

    @Test fun `multi-line define stays a preprocessor line`() {
        val text = "#define SQR(x) \\\n    ((x) * (x))\nfloat f(float y) { return SQR(y); }"
        assertEquals(GlslColors.MACRO, key_at(text, "SQR", 0))
        assertEquals(GlslColors.MACRO, key_at(text, "SQR", 1))
        assertEquals(GlslColors.FUNCTION_DECLARATION, key_at(text, "f"))
    }

    @Test fun `empty and garbage input do not throw`() {
        semantic_highlights("")
        semantic_highlights("{{{ ( ] ; } float (")
        semantic_highlights("void f(")
    }
}
