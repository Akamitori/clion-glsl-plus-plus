package dev.akamitori.glsl

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Our extensions that resolve to someone else's file type, grouped by that file type's display name.
 * `owner(ext)` returns null when the extension is ours. No plugin-name guessing: whoever the IDE maps
 * the extension to (another GLSL plugin, or the user's own File Types setting) is the conflict.
 */
internal fun foreign_owners(extensions: List<String>, owner: (String) -> String?): Map<String, List<String>> =
    extensions.mapNotNull { ext -> owner(ext)?.let { it to ext } }
        .groupBy({ it.first }, { it.second })

/** Once per IDE session: tell the user if some of our extensions are opened by another file type. */
class GlslConflictCheck : ProjectActivity {
    override suspend fun execute(project: Project) {
        val ftm = FileTypeManager.getInstance()
        val foreign = foreign_owners(GLSL_EXTENSIONS) { ext ->
            ftm.getFileTypeByExtension(ext).takeIf { it != GlslFileType }?.displayName
        }
        if (foreign.isEmpty() || !warned.compareAndSet(false, true)) return
        val list = foreign.entries.joinToString("<br>") { (type, exts) ->
            "<b>$type</b>: " + exts.joinToString(", ") { "*.$it" }
        }
        NotificationGroupManager.getInstance().getNotificationGroup("GLSL")
            .createNotification(
                "Some shader files are not handled by this GLSL plugin",
                "$list<br>These files get none of its features. Another GLSL plugin is probably installed " +
                    "(disable it), or the extensions were assigned in Settings | Editor | File Types.",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimpleExpiring("File Types…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "File Types")
            })
            .notify(project)
    }

    companion object {
        private val warned = AtomicBoolean(false)
    }
}
