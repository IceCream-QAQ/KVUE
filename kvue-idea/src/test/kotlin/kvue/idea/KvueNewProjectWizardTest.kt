package kvue.idea

import com.intellij.execution.RunManager
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.TestApplicationManager
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Path

class KvueNewProjectWizardTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor() = JAVA_21

    override fun setUp() {
        TestApplicationManager.getInstance()
        ExtensionPointName.create<Any>("com.intellij.postStartupActivity").point.unregisterExtensions(
            { name, adapter -> !(name == "Z.Z.Z.Z.Z" && adapter.pluginDescriptor.pluginId.idString == "com.intellij.modules.ultimate") }, false,
        )
        super.setUp()
    }

    fun testWizardIsRegisteredAndItsEntireFormCanBeCreated() {
        val wizard = GeneratorNewProjectWizard.EP_NAME.extensionList.filterIsInstance<KvueNewProjectWizard>().single()
        assertEquals("KVUE", wizard.name)
        val context = WizardContext(null, testRootDisposable)
        context.projectJdk = ModuleRootManager.getInstance(module).sdk
        val step = wizard.createStep(context)
        val form = panel { step.setupUI(this) }
        assertNotNull(step.baseData)
        assertTrue(form.componentCount > 0)
        assertTrue(step.baseData!!.name.startsWith("kvue-app"))
    }

    fun testCreatedProjectGetsTheSelectedJdkAndGradleRunConfigurations() {
        val root = Path.of(project.basePath!!)
        val sdk = requireNotNull(ModuleRootManager.getInstance(module).sdk)
        val previous = ProjectRootManager.getInstance(project).projectSdk
        val manager = RunManager.getInstance(project)
        try {
            KvueCreatedProjectService.configure(project, root, sdk)
            val dev = manager.allSettings.single { it.name == "KVUE 开发服务器" }
            val devSettings = (dev.configuration as ExternalSystemRunConfiguration).settings
            assertEquals(root.toAbsolutePath().normalize().toString(), devSettings.externalProjectPath)
            assertEquals(listOf("jsBrowserDevelopmentRun"), devSettings.taskNames)
            assertEquals("--continuous", devSettings.scriptParameters)
            assertEquals(dev, manager.selectedConfiguration)
            val production = manager.allSettings.single { it.name == "KVUE 生产打包" }
            assertEquals(listOf("jsBrowserDistribution"), (production.configuration as ExternalSystemRunConfiguration).settings.taskNames)
            assertSame(sdk, ProjectRootManager.getInstance(project).projectSdk)
        } finally {
            manager.allSettings.filter { it.name.startsWith("KVUE ") }.forEach(manager::removeConfiguration)
            WriteAction.run<RuntimeException> { ProjectRootManager.getInstance(project).projectSdk = previous }
        }
    }
}
