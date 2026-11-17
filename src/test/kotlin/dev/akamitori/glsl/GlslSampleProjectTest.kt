package dev.akamitori.glsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Checks testdata/sample-project (the project used for manual testing, see docs/testing-windows.md)
 * with the real bundled glslang, exactly as the annotator would: includes resolved from disk, the
 * project's target and defines, errors mapped per file. On Windows this exercises glslang.exe,
 * Windows paths and (with git's autocrlf) CRLF line endings. Skipped if bundleTools hasn't run.
 */
class GlslSampleProjectTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root = Path.of("testdata/sample-project").toAbsolutePath()
    private val options = CheckOptions(target = "vulkan1.3", defines = listOf("SCALE" to "2.0"))

    private fun glslang(): Path {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val exe = Path.of(if (windows) "build/bundled/bin/windows-x64/glslang.exe" else "build/bundled/bin/linux-x64/glslang").toAbsolutePath()
        assumeTrue("bundled glslang not built for this OS (run bundleTools)", Files.isRegularFile(exe))
        exe.toFile().setExecutable(true)
        return exe
    }

    /** Problems for `file` (relative to the sample project), compiled through `host` (default: itself). */
    private fun check(file: String, host: String = file): List<Problem> {
        val exe = glslang()
        val host_path = root.resolve(host)
        val read = { p: Path -> Files.readString(p) }
        val e = expand_includes(read(host_path), host_path.parent, listOf(root), main_path = host_path, read = read)
        val stage = host.substringAfterLast('.')
        val proc = ProcessBuilder(listOf(exe.toString()) + glslang_args(stage, options.target, tmp.newFile().toPath()))
            .directory(tmp.root).redirectErrorStream(true).start()
        proc.outputStream.use { it.write(inject_defines(e.text, options.defines).toByteArray()) }
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(10, TimeUnit.SECONDS))
        assertTrue("glslang failed to run: $out", glslang_ran(proc.exitValue()))
        val target = if (host == file) null else root.resolve(file)
        return problems_in(out, e, target)
    }

    @Test fun `project settings file matches what these tests assume`() {
        val xml = Files.readString(root.resolve(".idea/glsl.xml"))
        assertTrue(xml, xml.contains("name=\"target\" value=\"vulkan1.3\""))
        assertTrue(xml, xml.contains("name=\"defines\" value=\"SCALE=2.0\""))
    }

    @Test fun `shader frag has exactly its one deliberate error`() {
        assertEquals(
            listOf(Problem(11, "'undefined_thing' : undeclared identifier", "undefined_thing", false)),
            check("shader.frag"),
        )
    }

    @Test fun `vulkan frag is clean with the project's Vulkan target and SCALE define`() {
        assertEquals(emptyList<Problem>(), check("vulkan.frag"))
    }

    @Test fun `errors frag reports all five errors at once`() {
        assertEquals(listOf(8, 9, 10, 11, 12), check("errors.frag").map { it.line })
    }

    @Test fun `the include library is found through a host and is clean`() {
        val lib = root.resolve("common/lighting.glsl")
        val candidates = listOf("errors.frag", "shader.frag", "vulkan.frag").map { root.resolve(it) }
        val host = find_host(lib, candidates, listOf(root)) { Files.readString(it) }
        assertEquals(root.resolve("shader.frag"), host)  // errors.frag doesn't include it; then alphabetical
        assertEquals(emptyList<Problem>(), check("common/lighting.glsl", host = "shader.frag"))
    }

    @Test fun `include navigation resolves the sample's include`() {
        val text = Files.readString(root.resolve("shader.frag"))
        val (name, _) = include_at(text, text.indexOf("common/lighting.glsl"))!!
        assertEquals(root.resolve("common/lighting.glsl"), resolve_include(name, root, emptyList()))
    }
}
