package dev.akamitori.glsl

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Path

/** Machine-wide: where the tools are. Stored in the IDE config dir (glsl.xml). */
@Service(Service.Level.APP)
@State(name = "GlslSettings", storages = [Storage("glsl.xml")])
class GlslSettings : PersistentStateComponent<GlslSettings.State> {
    data class State(var analyzerPath: String = "", var glslangPath: String = "")

    private var current = State()

    override fun getState() = current
    override fun loadState(state: State) {
        current = state
    }

    companion object {
        fun getInstance(): GlslSettings = service()
    }
}

/**
 * Per project: how glslang should check this project's shaders. Stored in .idea/glsl.xml,
 * so it can be committed and shared with the rest of the team.
 */
@Service(Service.Level.PROJECT)
@State(name = "GlslProjectSettings", storages = [Storage("glsl.xml")])
class GlslProjectSettings : PersistentStateComponent<GlslProjectSettings.State> {
    /**
     * target: "opengl" or a glslang --target-env such as "vulkan1.3".
     * defines: whitespace-separated NAME or NAME=VALUE.
     * includeDirs: ';'-separated, relative to the project root or absolute.
     */
    data class State(var target: String = "opengl", var defines: String = "", var includeDirs: String = "")

    private var current = State()

    override fun getState() = current
    override fun loadState(state: State) {
        current = state
    }

    /** Snapshot for the checker; include dirs resolved against the project root. */
    fun check_options(project_dir: Path?): CheckOptions = CheckOptions(
        target = current.target,
        defines = parse_defines(current.defines),
        include_dirs = current.includeDirs.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { dir ->
            val p = Path.of(dir)
            if (p.isAbsolute || project_dir == null) p else project_dir.resolve(p).normalize()
        },
    )

    companion object {
        fun getInstance(project: Project): GlslProjectSettings = project.service()
    }
}

/** glslang targets offered in the settings; the value is passed as --target-env (except "opengl"). */
val GLSL_TARGETS = listOf("opengl", "vulkan1.0", "vulkan1.1", "vulkan1.2", "vulkan1.3", "vulkan1.4")

private fun target_label(target: String) = when (target) {
    "opengl" -> "OpenGL"
    else -> "Vulkan " + target.removePrefix("vulkan")
}

class GlslConfigurable(private val project: Project) : BoundConfigurable("GLSL") {
    override fun createPanel(): DialogPanel {
        val settings = GlslSettings.getInstance()
        val project_settings = GlslProjectSettings.getInstance(project)
        return panel {
            group("Error Checking (this project)") {
                row("Target:") {
                    comboBox(GLSL_TARGETS, com.intellij.ui.dsl.listCellRenderer.textListCellRenderer { it?.let(::target_label) ?: "" })
                        .bindItem({ project_settings.state.target }, { project_settings.state.target = it ?: "opengl" })
                        .comment("Vulkan enables Vulkan-only syntax such as <code>layout(set = 0)</code> and <code>push_constant</code>")
                }
                row("Defines:") {
                    textField()
                        .bindText({ project_settings.state.defines }, { project_settings.state.defines = it.trim() })
                        .comment("Separated by spaces, e.g. <code>MAX_LIGHTS=8 USE_SHADOWS</code>")
                        .align(AlignX.FILL)
                }
                row("Include folders:") {
                    textField()
                        .bindText({ project_settings.state.includeDirs }, { project_settings.state.includeDirs = it.trim() })
                        .comment("Separated by <code>;</code>, relative to the project root or absolute. " +
                            "The shader's own folder and the project root are always searched first.")
                        .align(AlignX.FILL)
                }
            }
            group("Tools (all projects)") {
                row("glsl_analyzer path:") {
                    textField()
                        .bindText({ settings.state.analyzerPath }, { settings.state.analyzerPath = it.trim() })
                        .comment("Empty = use the copy bundled with the plugin, else PATH")
                        .align(AlignX.FILL)
                }
                row("glslangValidator path:") {
                    textField()
                        .bindText({ settings.state.glslangPath }, { settings.state.glslangPath = it.trim() })
                        .comment("Used for error checking. Empty = bundled copy, else PATH")
                        .align(AlignX.FILL)
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        for (p in ProjectManager.getInstance().openProjects) {
            LspClientManager.getInstance(p).stopAndRestartClientsIfNeeded(GlslLspIntegrationProvider::class.java)
            DaemonCodeAnalyzer.getInstance(p).restart("GLSL settings changed")  // re-check with the new target/defines
        }
    }
}
