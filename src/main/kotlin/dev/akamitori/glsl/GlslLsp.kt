package dev.akamitori.glsl

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem
import com.intellij.util.system.CpuArch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

fun find_glsl_analyzer(): Path? = find_executable(GlslSettings.getInstance().state.analyzerPath, "glsl_analyzer")

/** glslang ships as `glslang` in newer releases, with `glslangValidator` kept as an alias. */
fun find_glslang(): Path? = find_executable(GlslSettings.getInstance().state.glslangPath, "glslangValidator", "glslang")

/**
 * Lookup order:
 *  1. the configured path, if set (and only that, so a wrong setting is visible rather than papered over);
 *  2. the copy bundled with the plugin for this OS (Linux / Windows x64);
 *  3. PATH, then common install dirs. A CLion launched from the desktop often lacks the shell PATH.
 */
private fun find_executable(configured: String, vararg names: String): Path? {
    if (configured.isNotBlank()) {
        val p = Path.of(configured)
        return if (Files.isExecutable(p)) p else null
    }
    for (name in names) bundled_executable(name)?.let { return it }
    for (name in names) {
        PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS(exe_name(name))?.let { return it.toPath() }
    }
    if (SystemInfo.isWindows) return null
    val home = System.getProperty("user.home")
    val dirs = listOf("$home/.local/bin", "/usr/local/bin", "/opt/homebrew/bin")
    return names.asSequence()
        .flatMap { name -> dirs.asSequence().map { Path.of(it, name) } }
        .firstOrNull { Files.isExecutable(it) }
}

private fun exe_name(name: String) = if (SystemInfo.isWindows) "$name.exe" else name

/** `<plugin dir>/bin/<os>-x64/<name>`, see the bundleTools task in build.gradle.kts. */
private fun bundled_executable(name: String): Path? {
    if (!CpuArch.isIntel64()) return null
    val platform = when {
        SystemInfo.isLinux -> "linux-x64"
        SystemInfo.isWindows -> "windows-x64"
        else -> return null
    }
    val plugin_dir = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath ?: return null
    val exe = plugin_dir.resolve("bin").resolve(platform).resolve(exe_name(name))
    if (!Files.isRegularFile(exe)) return null
    // Plugin installation doesn't reliably keep the executable bit from the zip.
    if (!SystemInfo.isWindows && !Files.isExecutable(exe)) exe.toFile().setExecutable(true)
    return exe.takeIf { Files.isExecutable(it) }
}

private const val PLUGIN_ID = "dev.akamitori.glsl"

class GlslLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        if (file.fileType != GlslFileType) return
        val exe = find_glsl_analyzer()
        if (exe == null) {
            warn_missing_once(project)
            return
        }
        serverStarter.ensureServerStarted(GlslLspServerDescriptor(project, exe))
    }

    override fun createLspServerWidgetItem(lspServer: LspServer, currentFile: VirtualFile?) =
        LspServerWidgetItem(lspServer, currentFile, GlslIcons.FILE, GlslConfigurable::class.java)

    private fun warn_missing_once(project: Project) {
        if (!warned.compareAndSet(false, true)) return
        val configured = GlslSettings.getInstance().state.analyzerPath
        val detail = if (configured.isNotBlank()) {
            "The configured path <code>$configured</code> is not an executable file."
        } else {
            "No bundled copy for this platform, and not found on PATH or in the usual install folders."
        }
        NotificationGroupManager.getInstance().getNotificationGroup("GLSL")
            .createNotification(
                "glsl_analyzer not found",
                "$detail Completion, navigation and diagnostics are disabled; highlighting still works.",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimpleExpiring("Configure…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, GlslConfigurable::class.java)
            })
            .notify(project)
    }

    companion object {
        private val warned = AtomicBoolean(false)
    }
}

class GlslLspServerDescriptor(project: Project, private val exe: Path) :
    ProjectWideLspServerDescriptor(project, "GLSL") {

    override fun isSupportedFile(file: VirtualFile) = file.fileType == GlslFileType

    override fun createCommandLine() = GeneralCommandLine(exe.toString()).withWorkDirectory(project.basePath)
}
