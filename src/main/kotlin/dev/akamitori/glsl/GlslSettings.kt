package dev.akamitori.glsl

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

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

class GlslConfigurable : BoundConfigurable("GLSL") {
    override fun createPanel(): DialogPanel {
        val settings = GlslSettings.getInstance()
        return panel {
            row("glsl_analyzer path:") {
                textField()
                    .bindText({ settings.state.analyzerPath }, { settings.state.analyzerPath = it.trim() })
                    .comment("Empty = use the copy bundled with the plugin, else PATH")
                    .align(AlignX.FILL)
            }
            row("glslangValidator path:") {
                textField()
                    .bindText({ settings.state.glslangPath }, { settings.state.glslangPath = it.trim() })
                    .comment("Used for error checking (undeclared identifiers, type errors). Empty = bundled copy, else PATH")
                    .align(AlignX.FILL)
            }
        }
    }

    override fun apply() {
        super.apply()
        for (p in ProjectManager.getInstance().openProjects) {
            LspServerManager.getInstance(p).stopAndRestartIfNeeded(GlslLspServerSupportProvider::class.java)
        }
    }
}
