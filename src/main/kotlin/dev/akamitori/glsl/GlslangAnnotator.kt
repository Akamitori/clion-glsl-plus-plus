package dev.akamitori.glsl

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Error checking via glslang (glslangValidator), since glsl_analyzer reports no diagnostics.
 *
 * glslang runs in plain OpenGL mode: the SPIR-V modes (-V/-G) are the only ones that process
 * #include, but they also enforce SPIR-V rules (layout(location) on every uniform, no loose uniforms
 * for Vulkan) that are wrong for ordinary OpenGL shaders. So we expand #include ourselves and give
 * every included file its own source-string number via `#line`, which lets errors be mapped back:
 * string 0 is this file, anything else is reported on the #include line that pulled it in.
 *
 * Only files with a stage extension are checked. A bare .glsl is usually an include library that
 * doesn't compile on its own (no #version, no main), so checking it would only produce noise.
 */
// glslang needs no index, so it must also run while CLion is indexing (dumb mode); without DumbAware
// the daemon skips it then, and errors only appear after the next edit.
class GlslangAnnotator : ExternalAnnotator<GlslangAnnotator.Input, List<Problem>>(), DumbAware {

    class Input(val text: String, val dir: Path, val stage: String, val project_dir: Path?)


    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Input? {
        val vfile = file.virtualFile ?: return null
        val stage = STAGES[vfile.extension?.lowercase()] ?: return null
        val dir = vfile.parent?.toNioPathOrNull() ?: return null
        val project_dir = file.project.basePath?.let { Path.of(it) }
        return Input(editor.document.text, dir, stage, project_dir)
    }

    override fun doAnnotate(input: Input): List<Problem> {
        val exe = find_glslang() ?: return emptyList()
        val expanded = expand_includes(input.text, input.dir, input.project_dir, ::current_text)

        val cmd = GeneralCommandLine(exe.toString(), "--stdin", "-S", input.stage)
            .withWorkDirectory(input.dir.toFile())
            .withCharset(StandardCharsets.UTF_8)
        val handler = CapturingProcessHandler(cmd)
        handler.processInput.use { it.write(expanded.text.toByteArray(StandardCharsets.UTF_8)) }
        val out = handler.runProcess(5000)
        if (out.isTimeout) return emptyList()
        return parse_glslang_output(out.stdout + "\n" + out.stderr, expanded.includes)
    }

    override fun apply(file: PsiFile, problems: List<Problem>, holder: AnnotationHolder) {
        val text = file.viewProvider.document?.charsSequence ?: return
        for (p in problems) {
            val range = problem_range(text, p) ?: continue
            val severity = if (p.warning) HighlightSeverity.WARNING else HighlightSeverity.ERROR
            holder.newAnnotation(severity, p.message).range(range).create()
        }
    }

    /** The editor's in-memory text if the file is open (unsaved edits, e.g. right after a rename), else disk. */
    private fun current_text(path: Path): String {
        val vf = LocalFileSystem.getInstance().findFileByNioFile(path)
        val doc = vf?.let { ReadAction.compute<Document?, Throwable> { FileDocumentManager.getInstance().getCachedDocument(it) } }
        return doc?.let { ReadAction.compute<String, Throwable> { it.text } } ?: Files.readString(path)
    }
}

private fun com.intellij.openapi.vfs.VirtualFile.toNioPathOrNull(): Path? =
    try { toNioPath() } catch (_: UnsupportedOperationException) { null }

// ---- pure helpers (unit-tested in GlslangAnnotatorTest) ----

/** line is 0-based in this file; token is the quoted name from the message, if any. */
data class Problem(val line: Int, val message: String, val token: String?, val warning: Boolean)

/** Where an included source string came from: the #include line in *this* file (0-based) and the header name. */
data class IncludeSite(val line: Int, val name: String)

class Expanded(val text: String, val includes: Map<Int, IncludeSite>)

internal val STAGES = mapOf(
    "vert" to "vert", "vsh" to "vert", "frag" to "frag", "fsh" to "frag",
    "geom" to "geom", "comp" to "comp", "tesc" to "tesc", "tese" to "tese",
    "mesh" to "mesh", "task" to "task",
    "rgen" to "rgen", "rint" to "rint", "rahit" to "rahit", "rchit" to "rchit",
    "rmiss" to "rmiss", "rcall" to "rcall",
)
private val DIAG = Regex("^(ERROR|WARNING): (\\d+):(\\d+): (.*)$")
private val QUOTED = Regex("'([^']*)'")
private val INCLUDE = Regex("^\\s*#\\s*include\\s*[<\"]([^>\"]+)[>\"]")
private val INCLUDE_EXT = Regex("^\\s*#\\s*extension\\s+GL_(GOOGLE_include_directive|ARB_shading_language_include)\\b")

/**
 * Inlines #include files so glslang can run in plain OpenGL mode. Each included file gets its own
 * source-string number via `#line 1 N`, and `#line <next> <parent>` restores numbering afterwards,
 * so errors map back: string 0 is this file, others belong to the #include line in `includes`.
 * Unresolvable or recursive includes are left as-is so glslang reports them on the right line.
 */
internal fun expand_includes(text: String, dir: Path, project_dir: Path?, read: (Path) -> String): Expanded {
    val includes = HashMap<Int, IncludeSite>()
    var next_string = 1
    val out = StringBuilder()

    fun expand(text: String, dir: Path, string_no: Int, site: IncludeSite?, stack: Set<Path>) {
        text.lineSequence().forEachIndexed { i, line ->
            val m = INCLUDE.find(line)
            if (m == null) {
                // Plain mode would reject this extension; we already did its job.
                if (INCLUDE_EXT.containsMatchIn(line)) out.append('\n') else out.append(line).append('\n')
                return@forEachIndexed
            }
            val name = m.groupValues[1]
            val path = resolve_include(name, dir, project_dir)
            val my_site = site ?: IncludeSite(i, name)  // nested includes report on the top-level #include
            if (path == null || path in stack || stack.size > 16) {
                out.append(line).append('\n')
                return@forEachIndexed
            }
            val inc_no = next_string++
            includes[inc_no] = my_site
            out.append("#line 1 ").append(inc_no).append('\n')
            // plusElement, not `+`: Path is Iterable<Path>, so `stack + path` would add its name components.
            expand(read(path), path.parent, inc_no, my_site, stack.plusElement(path))
            out.append("#line ").append(i + 2).append(' ').append(string_no).append('\n')
        }
    }

    expand(text, dir, 0, null, emptySet())
    return Expanded(out.toString(), includes)
}

private fun resolve_include(name: String, dir: Path, project_dir: Path?): Path? =
    listOfNotNull(dir, project_dir)
        .map { it.resolve(name).normalize() }
        .firstOrNull { Files.isRegularFile(it) }

/** glslang's `ERROR: <string>:<line>: <message>` lines -> problems in this file. */
internal fun parse_glslang_output(output: String, includes: Map<Int, IncludeSite>): List<Problem> =
    output.lineSequence()
        .mapNotNull { DIAG.matchEntire(it.trim()) }
        .mapNotNull { m ->
            val (kind, string_no, line_no, msg) = m.destructured
            val message = msg.trim()
            if (message.endsWith("compilation terminated") || message.isEmpty()) return@mapNotNull null
            val token = QUOTED.find(message)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            val warning = kind == "WARNING"
            val string = string_no.toInt()
            if (string == 0) {
                Problem(line_no.toInt() - 1, message, token, warning)
            } else {
                val inc = includes[string] ?: return@mapNotNull null
                Problem(inc.line, "In ${inc.name}:${line_no}: $message", null, warning)
            }
        }
        .toList()

/** The quoted token on that line if we can find it, else the line without leading/trailing blanks. */
internal fun problem_range(text: CharSequence, p: Problem): TextRange? {
    if (text.isEmpty()) return null
    val line_starts = buildList { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
    val line = p.line.coerceIn(0, line_starts.size - 1)
    val start = line_starts[line]
    val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
    val line_text = text.subSequence(start, end).toString()
    if (p.token != null) {
        val i = Regex("\\b${Regex.escape(p.token)}\\b").find(line_text)?.range?.first
        if (i != null) return TextRange(start + i, start + i + p.token.length)
    }
    val first = line_text.indexOfFirst { !it.isWhitespace() }
    if (first < 0) return TextRange(start, end)
    val last = line_text.indexOfLast { !it.isWhitespace() }
    return TextRange(start + first, start + last + 1)
}
