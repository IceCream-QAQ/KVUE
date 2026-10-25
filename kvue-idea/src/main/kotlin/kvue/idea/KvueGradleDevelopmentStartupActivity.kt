package kvue.idea

import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManager
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.jetbrains.plugins.gradle.util.GradleConstants

/** Enabled only by our runIde task, not by installing the plugin in a user's IDE. */
class KvueGradleDevelopmentStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!java.lang.Boolean.getBoolean("kvue.development.refreshGradleOnStartup")) return
        if (TrustedProjects.isProjectTrusted(project)) {
            refreshWhenInitialized(project)
            return
        }
        TrustedProjectsListener.onceWhenProjectTrusted(project, java.util.function.Consumer { trusted ->
            if (trusted == project) refreshWhenInitialized(project)
        })
    }

    private fun refreshWhenInitialized(project: Project) {
        ExternalProjectsManager.getInstance(project).runWhenInitialized {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && TrustedProjects.isProjectTrusted(project)) {
                    Logger.getInstance(KvueGradleDevelopmentStartupActivity::class.java)
                        .info("Refreshing development Gradle model to pick up current KVUE SNAPSHOT binaries.")
                    ExternalSystemUtil.refreshProjects(
                        ImportSpecBuilder(project, GradleConstants.SYSTEM_ID).use(ProgressExecutionMode.IN_BACKGROUND_ASYNC),
                    )
                }
            }
        }
    }
}
