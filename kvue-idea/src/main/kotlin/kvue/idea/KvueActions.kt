package kvue.idea

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages

class RefreshKvueDeclarationsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = event.project != null }
    override fun actionPerformed(event: AnActionEvent) { event.project?.service<KvueProjectService>()?.requestExternalRefresh() }
}

class KvueAnalysisStatusAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = event.project != null }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        Messages.showInfoMessage(project, project.service<KvueProjectService>().statusText(), "KVUE Analysis")
    }
}
