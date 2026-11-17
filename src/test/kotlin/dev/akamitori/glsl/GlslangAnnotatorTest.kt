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

    private fun expand(text: String, dir: Path = tmp.root.toPath(), search_dirs: List<Path> = emptyList()) =
        expand_includes(text, dir, search_dirs) { Files.readString(it) }

    // ---- #include expansion ----

    @Test fun `include is inlined with line mapping`() {
        write("common/light.glsl", "float lambert() { return 1.0; }\n")
        val e = expand("#version 460\n#include \"common/light.glsl\"\nvoid main() {}\n")
        assertEquals(
            "#version 460\n#line 1 1\nfloat lambert() { return 1.0; }\n\n#line 3 0\nvoid main() {}\n\n",
            e.text,
        )
        assertEquals(
            SourceString(tmp.root.toPath().resolve("common/light.glsl"), parent = 0, line_in_parent = 1, name = "common/light.glsl"),
            e.strings[1],
        )
        assertEquals(2, e.strings.size)  // 0 = main, 1 = the include
    }

    @Test fun `angle includes and project-root fallback`() {
        write("lib/a.glsl", "float a;\n")
        val shader_dir = Files.createDirectories(tmp.root.toPath().resolve("shaders"))
        val e = expand("#include <lib/a.glsl>\n", shader_dir, listOf(tmp.root.toPath()))
        assertTrue(e.text, e.text.contains("float a;"))
    }

    @Test fun `nested includes record their parent`() {
        write("a.glsl", "#include \"b.glsl\"\n")
        write("b.glsl", "float b;\n")
        val e = expand("// x\n#include \"a.glsl\"\n")
        assertTrue(e.text.contains("float b;"))
        assertEquals(SourceString(tmp.root.toPath().resolve("a.glsl"), 0, 1, "a.glsl"), e.strings[1])
        assertEquals(SourceString(tmp.root.toPath().resolve("b.glsl"), 1, 0, "b.glsl"), e.strings[2])
    }

    @Test fun `missing and recursive includes are left in place`() {
        val missing = expand("#include \"nope.glsl\"\n")
        assertEquals("#include \"nope.glsl\"\n\n", missing.text)
        assertEquals(setOf(0), missing.strings.keys)

        write("self.glsl", "#include \"self.glsl\"\nfloat s;\n")
        val rec = expand("#include \"self.glsl\"\n")
        assertEquals(setOf(0, 1), rec.strings.keys)
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
        val e = expand_includes("#include \"a.glsl\"\n", tmp.root.toPath(), emptyList()) { p ->
            if (p == inc) "float in_editor;\n" else Files.readString(p)
        }
        assertTrue(e.text.contains("in_editor"))
        assertFalse(e.text.contains("on_disk"))
    }

    @Test fun `configured include folders are searched after the file's own folder`() {
        write("own/x.glsl", "float own;\n")
        write("extra/x.glsl", "float extra;\n")
        write("extra/only_here.glsl", "float only_here;\n")
        val own = tmp.root.toPath().resolve("own")
        val extra = tmp.root.toPath().resolve("extra")
        assertTrue(expand("#include \"x.glsl\"\n", own, listOf(extra)).text.contains("float own;"))
        assertTrue(expand("#include \"only_here.glsl\"\n", own, listOf(extra)).text.contains("float only_here;"))
    }

    // ---- defines and target ----

    @Test fun `parse defines`() {
        assertEquals(
            listOf("A" to null, "B" to "1", "C" to "foo(x)", "EMPTY" to ""),
            parse_defines("  A B=1\n C=foo(x)  EMPTY= 9bad =x"),
        )
        assertEquals(emptyList<Pair<String, String?>>(), parse_defines(""))
    }

    @Test fun `defines go after version and keep line numbers`() {
        val src = "#version 460\nvoid main() {}\n"
        assertEquals(
            "#version 460\n#define A\n#define B 2\n#line 2 0\nvoid main() {}\n",
            inject_defines(src, listOf("A" to null, "B" to "2")),
        )
        // A leading comment before #version is fine; without #version, defines go on top.
        assertTrue(inject_defines("// hi\n#version 460\nx\n", listOf("A" to null)).startsWith("// hi\n#version 460\n#define A\n#line 3 0\n"))
        assertEquals("#define A\n#line 1 0\nfloat x;\n", inject_defines("float x;\n", listOf("A" to null)))
        assertEquals(src, inject_defines(src, emptyList()))
    }

    @Test fun `glslang arguments per target`() {
        val spv = Path.of("out.spv")
        assertEquals(listOf("--stdin", "-S", "frag"), glslang_args("frag", "opengl", spv))
        assertEquals(
            listOf("--stdin", "-S", "comp", "-V", "--target-env", "vulkan1.3", "-o", "out.spv"),
            glslang_args("comp", "vulkan1.3", spv),
        )
    }

    @Test fun `stdin as source name is this file`() {
        // SPIR-V mode names string 0 "stdin" until the first #line directive.
        assertEquals(
            listOf(Problem(7, "'x' : undeclared identifier", "x", false)),
            problems_in("stdin\nERROR: stdin:8: 'x' : undeclared identifier\nERROR: Linking fragment stage: Missing entry point\n", main_only, null),
        )
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
            problems_in(out, main_only, null),
        )
    }

    private val main_only = Expanded("", mapOf(0 to SourceString(null, null, -1, "")))

    /** main (0) includes lib.glsl (1) on line 1; lib includes util.glsl (2) on line 4. */
    private val chain = Expanded("", mapOf(
        0 to SourceString(Path.of("/p/main.frag"), null, -1, "main.frag"),
        1 to SourceString(Path.of("/p/lib.glsl"), 0, 1, "lib.glsl"),
        2 to SourceString(Path.of("/p/util.glsl"), 1, 4, "util.glsl"),
    ))
    private val chain_out = """
        ERROR: 0:9: 'in_main' : undeclared identifier
        ERROR: 1:3: 'in_lib' : undeclared identifier
        ERROR: 2:2: 'in_util' : undeclared identifier
        ERROR: 7:1: 'x' : unknown string
    """.trimIndent()

    @Test fun `main file view puts included errors on the include line`() {
        assertEquals(
            listOf(
                Problem(8, "'in_main' : undeclared identifier", "in_main", false),
                Problem(1, "In lib.glsl:3: 'in_lib' : undeclared identifier", null, false),
                Problem(1, "In util.glsl:2: 'in_util' : undeclared identifier", null, false),
            ),
            problems_in(chain_out, chain, null),
        )
    }

    @Test fun `library view keeps only its own errors and those of what it includes`() {
        assertEquals(
            listOf(
                Problem(2, "'in_lib' : undeclared identifier", "in_lib", false),
                Problem(4, "In util.glsl:2: 'in_util' : undeclared identifier", null, false),
            ),
            problems_in(chain_out, chain, Path.of("/p/lib.glsl")),
        )
        assertEquals(listOf(Problem(1, "'in_util' : undeclared identifier", "in_util", false)),
            problems_in(chain_out, chain, Path.of("/p/util.glsl")))
        assertEquals(emptyList<Problem>(), problems_in(chain_out, chain, Path.of("/p/unrelated.glsl")))
    }

    // ---- finding a host shader for a library ----

    @Test fun `host is a shader that includes the library, directly or not, preferring the same folder`() {
        val lib = write("shaders/common/lighting.glsl", "float lambert() { return 1.0; }\n")
        write("shaders/common/mid.glsl", "#include \"lighting.glsl\"\n")
        val far = write("other/far.frag", "#include \"../shaders/common/lighting.glsl\"\nvoid main() {}\n")
        val near = write("shaders/common/near.frag", "#include \"mid.glsl\"\nvoid main() {}\n")
        val unrelated = write("shaders/common/aaa.frag", "void main() {}\n")
        val read = { p: Path -> Files.readString(p) }
        assertEquals(near, find_host(lib, listOf(far, unrelated, near), emptyList(), read))
        assertEquals(far, find_host(lib, listOf(far, unrelated), emptyList(), read))
        assertEquals(null, find_host(lib, listOf(unrelated), emptyList(), read))
        assertEquals(null, find_host(lib, listOf(tmp.root.toPath().resolve("gone.frag")), emptyList(), read))  // unreadable
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

    private fun bundled_glslang(): Path? {
        val windows = System.getProperty("os.name").startsWith("Windows")
        // Absolute: the process runs with the temp folder as its working directory.
        val exe = Path.of(if (windows) "build/bundled/bin/windows-x64/glslang.exe" else "build/bundled/bin/linux-x64/glslang").toAbsolutePath()
        assumeTrue("bundled glslang not built for this OS (run bundleTools)", Files.isRegularFile(exe))
        exe.toFile().setExecutable(true)
        return exe
    }

    /** Runs the real glslang the way the annotator does: expand includes, inject defines, map output. */
    private fun check(shader: String, options: CheckOptions = CheckOptions(), target: Path? = null): List<Problem> {
        val exe = bundled_glslang()!!
        val e = expand(shader, search_dirs = options.include_dirs)
        val spv = tmp.newFile().toPath()  // unique per call
        val proc = ProcessBuilder(listOf(exe.toString()) + glslang_args("frag", options.target, spv))
            .directory(tmp.root).redirectErrorStream(true).start()
        proc.outputStream.use { it.write(inject_defines(e.text, options.defines).toByteArray()) }
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(10, TimeUnit.SECONDS))
        return problems_in(out, e, target)
    }

    private fun undeclared(line: Int, name: String) = Problem(line, "'$name' : undeclared identifier", name, false)

    @Test fun `real glslang finds the undeclared identifier through an include`() {
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
        assertEquals(listOf(undeclared(7, "undefined_thing")), check(shader))
    }

    private val vulkan_shader = """
        #version 460
        layout(set = 0, binding = 0) uniform sampler2D tex;
        layout(push_constant) uniform PC { vec4 tint; } pc;
        layout(location = 0) in vec2 uv;
        layout(location = 0) out vec4 color;
        void main() {
            color = texture(tex, uv) * pc.tint * SCALE;
            undefined_thing = 1;
        }
    """.trimIndent()

    @Test fun `real glslang in Vulkan mode accepts set and push_constant`() {
        val options = CheckOptions(target = "vulkan1.3", defines = listOf("SCALE" to "2.0"))
        assertEquals(listOf(undeclared(7, "undefined_thing")), check(vulkan_shader, options))
        assertFalse("no .spv left next to the shader", Files.exists(tmp.root.toPath().resolve("frag.spv")))
    }

    @Test fun `real glslang in OpenGL mode rejects Vulkan-only syntax`() {
        val problems = check(vulkan_shader, CheckOptions(defines = listOf("SCALE" to "2.0")))
        assertTrue(problems.toString(), problems.isNotEmpty() && problems.none { it.token == "undefined_thing" && it.line != 7 })
        assertTrue(problems.toString(), problems.first().line in 1..2)  // set= / push_constant
    }

    @Test fun `real glslang sees injected defines`() {
        // Without the define, SCALE is undeclared on line 7 (0-based 6); with it, only undefined_thing remains.
        val options = CheckOptions(target = "vulkan1.3")
        assertEquals(listOf(undeclared(6, "SCALE")), check(vulkan_shader, options))
    }

    @Test fun `real glslang checks a library through its host`() {
        val lib = write("common/lighting.glsl", "float lambert(vec3 n, vec3 l) {\n    return max(dot(n, l), 0.0) * not_declared;\n}\n")
        val shader = "#version 460 core\n#include \"common/lighting.glsl\"\nout vec4 c;\nvoid main() { c = vec4(lambert(vec3(1.0), vec3(1.0))); }\n"
        // As seen from the library: the error is on its own line 2 (0-based 1).
        assertEquals(listOf(undeclared(1, "not_declared")), check(shader, target = lib))
        // As seen from the shader: reported on its #include line.
        assertEquals(listOf(Problem(1, "In common/lighting.glsl:2: 'not_declared' : undeclared identifier", null, false)), check(shader))
    }
}
