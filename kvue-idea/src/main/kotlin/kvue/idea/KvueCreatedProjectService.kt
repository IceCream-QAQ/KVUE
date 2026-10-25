package kvue.idea

import com.intellij.execution.RunManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
import org.jetbrains.plugins.gradle.service.project.open.linkAndSyncGradleProject
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class KvueCreatedProjectService(private val project: Project, private val scope: CoroutineScope) {
    fun openAndSync(root: Path, entry: Path) {
        scope.launch {
            withContext(Dispatchers.EDT) {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(entry)?.let {
                    FileEditorManager.getInstance(project).openFile(it, true)
                }
            }
            linkAndSyncGradleProject(project, root.toString())
        }
    }

    companion object {
        internal fun configure(project: Project, root: Path, sdk: Sdk) {
            WriteAction.run<RuntimeException> { ProjectRootManager.getInstance(project).projectSdk = sdk }
            val manager = RunManager.getInstance(project)
            val factory = GradleExternalTaskConfigurationType.getInstance().configurationFactories.single()
            for ((name, task) in listOf("KVUE 开发服务器" to "jsBrowserDevelopmentRun", "KVUE 生产打包" to "jsBrowserDistribution")) {
                val configuration = manager.createConfiguration(name, factory)
                (configuration.configuration as ExternalSystemRunConfiguration).settings.apply {
                    externalProjectPath = root.toAbsolutePath().normalize().toString()
                    taskNames = listOf(task)
                    if (task == "jsBrowserDevelopmentRun") scriptParameters = "--continuous"
                }
                manager.addConfiguration(configuration)
                if (task == "jsBrowserDevelopmentRun") manager.selectedConfiguration = configuration
            }
        }
    }
}
