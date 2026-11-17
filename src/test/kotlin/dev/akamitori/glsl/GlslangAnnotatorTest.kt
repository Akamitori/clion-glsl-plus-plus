package dev.akamitori.glsl

import com.intellij.openapi.util.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class GlslangAnnotatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(rel: String, text: String): Path {
        val p = tmp.root.toPath().resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
        return p
    }

    private fun expand(text: String, dir: Path = tmp.root.toPath()) =
        expand_includes(text, dir, null) { Files.readString(it) }

    // ---- #include expansion ----

    @Test fun `include is inlined with line mapping`() {
        write("common/light.glsl", "float lambert() { return 1.0; }\n")
        val e = expand("#version 460\n#include \"common/light.glsl\"\nvoid main() {}\n")
        assertEquals(
            "#version 460\n#line 1 1\nfloat lambert() { return 1.0; }\n\n#line 3 0\nvoid main() {}\n\n",
            e.text,
        )
        assertEquals(mapOf(1 to IncludeSite(1, "common/light.glsl")), e.includes)
    }

    @Test fun `angle includes and project-root fallback`() {
        write("lib/a.glsl", "float a;\n")
        val shader_dir = Files.createDirectories(tmp.root.toPath().resolve("shaders"))
        val e = expand_includes("#include <lib/a.glsl>\n", shader_dir, tmp.root.toPath()) { Files.readString(it) }
        assertTrue(e.text, e.text.contains("float a;"))
    }

    @Test fun `nested include reports on the top-level include line`() {
        write("a.glsl", "#include \"b.glsl\"\n")
        write("b.glsl", "float b;\n")
        val e = expand("// x\n#include \"a.glsl\"\n")
        assertTrue(e.text.contains("float b;"))
        assertEquals(setOf(IncludeSite(1, "a.glsl")), e.includes.values.toSet())
        assertEquals(2, e.includes.size)  // a.glsl and b.glsl get their own source-string numbers
    }

    @Test fun `missing and recursive includes are left in place`() {
        val missing = expand("#include \"nope.glsl\"\n")
        assertEquals("#include \"nope.glsl\"\n\n", missing.text)
        assertTrue(missing.includes.isEmpty())

        write("self.glsl", "#include \"self.glsl\"\nfloat s;\n")
        val rec = expand("#include \"self.glsl\"\n")
        assertEquals(1, rec.includes.size)
        assertTrue(rec.text.contains("float s;"))
    }

    @Test fun `include extension line is blanked but line count is kept`() {
        val src = "#version 460\n#extension GL_GOOGLE_include_directive : require\nvoid main() {}\n"
        val e = expand(src)
        assertFalse(e.text.contains("GL_GOOGLE_include_directive"))
        assertEquals(src.lines().size, e.text.lines().size - 1)  // expand adds one trailing newline
    }

    @Test fun `unsaved editor text is what gets inlined`() {
        val inc = write("a.glsl", "float on_disk;\n")
        val e = expand_includes("#include \"a.glsl\"\n", tmp.root.toPath(), null) { p ->
            if (p == inc) "float in_editor;\n" else Files.readString(p)
        }
        assertTrue(e.text.contains("in_editor"))
        assertFalse(e.text.contains("on_disk"))
    }

    // ---- glslang output ----

    @Test fun `parses errors in this file`() {
        val out = """
            stdin
            ERROR: 0:12: 'undefined_thing' : undeclared identifier
            ERROR: 0:12: '' : compilation terminated
            WARNING: 0:3: 'f' : something odd
            ERROR: 2 compilation errors.  No code generated.
        """.trimIndent()
        assertEquals(
            listOf(
                Problem(11, "'undefined_thing' : undeclared identifier", "undefined_thing", false),
                Problem(2, "'f' : something odd", "f", true),
            ),
            parse_glslang_output(out, emptyMap()),
        )
    }

    @Test fun `errors in included code land on the include line`() {
        val out = "ERROR: 1:4: 'oops' : undeclared identifier\nERROR: 7:1: 'x' : unknown string\n"
        assertEquals(
            listOf(Problem(1, "In light.glsl:4: 'oops' : undeclared identifier", null, false)),
            parse_glslang_output(out, mapOf(1 to IncludeSite(1, "light.glsl"))),
        )
    }

    // ---- error ranges ----

    @Test fun `range is the quoted token, else the trimmed line`() {
        val text = "void main() {\n    undefined_thing = 3;\n}"
        val start = text.indexOf("undefined_thing")
        assertEquals(TextRange(start, start + "undefined_thing".length), problem_range(text, Problem(1, "", "undefined_thing", false)))
        val line = text.indexOf("undefined_thing")
        assertEquals(TextRange(line, text.indexOf(";") + 1), problem_range(text, Problem(1, "", null, false)))
        assertEquals(TextRange(text.length - 1, text.length), problem_range(text, Problem(99, "", null, false)))  // clamped
        assertEquals(null, problem_range("", Problem(0, "", null, false)))
    }

    // ---- end to end with the real (bundled) glslang, when available ----

    @Test fun `real glslang finds the undeclared identifier through an include`() {
        val exe = listOf("build/bundled/bin/linux-x64/glslang", "build/bundled/bin/windows-x64/glslang.exe")
            .map { Path.of(it) }
            .firstOrNull { Files.isRegularFile(it) && it.toString().endsWith(".exe") == System.getProperty("os.name").startsWith("Windows") }
        assumeTrue("bundled glslang not built for this OS (run bundleTools)", exe != null)
        exe!!.toFile().setExecutable(true)

        write("common/lighting.glsl", "float lambert(vec3 n, vec3 l) { return max(dot(n, l), 0.0); }\n")
        val shader = """
            #version 460 core
            #include "common/lighting.glsl"
            uniform vec3 u_light_dir;
            out vec4 frag_color;
            void main() {
                float lit = lambert(vec3(0.0), u_light_dir);
                frag_color = vec4(lit);
                undefined_thing = 3;
            }
        """.trimIndent()
        val e = expand(shader)
        val proc = ProcessBuilder(exe.toString(), "--stdin", "-S", "frag").redirectErrorStream(true).start()
        proc.outputStream.use { it.write(e.text.toByteArray()) }
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(10, TimeUnit.SECONDS))
        assertEquals(out, listOf(Problem(7, "'undefined_thing' : undeclared identifier", "undefined_thing", false)), parse_glslang_output(out, e.includes))
    }
}
