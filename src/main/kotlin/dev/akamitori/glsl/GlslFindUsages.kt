package dev.akamitori.glsl

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiManager
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation

/**
 * Find Usages for GLSL, bound to Alt+F7 and enabled only in GLSL files.
 *
 * glsl_analyzer has no references support, and CLion Nova sends the stock Find Usages to its C++
 * backend, so this uses the same occurrence search as rename (same scoping rules) and shows the
 * result in the regular Find tool window.
 */
class GlslFindUsagesAction : AnAction(), DumbAware, GlslEditorAction {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.getData(CommonDataKeys.EDITOR) != null && e.getData(CommonDataKeys.PSI_FILE) is GlslFile
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val found = find_symbol_occurrences(project, editor, file, "find usages of") ?: return

        val psi = PsiManager.getInstance(project)
        val usages: List<Usage> = found.ranges.flatMap { (vf, ranges) ->
            val psi_file = psi.findFile(vf) ?: return@flatMap emptyList()
            // The PSI is flat, so each usage is a range within the file itself.
            ranges.sortedBy { it.startOffset }.map { UsageInfo2UsageAdapter(UsageInfo(psi_file, it.startOffset, it.endOffset)) }
        }
        val presentation = UsageViewPresentation().apply {
            tabText = "'${found.name}'"
            searchString = found.name
            codeUsagesString = "Usages of '${found.name}'"
            isOpenInNewTab = false
        }
        UsageViewManager.getInstance(project).showUsages(UsageTarget.EMPTY_ARRAY, usages.toTypedArray(), presentation)
    }
}
