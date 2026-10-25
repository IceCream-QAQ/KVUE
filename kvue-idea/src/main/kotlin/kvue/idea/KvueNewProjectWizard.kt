package kvue.idea

import com.intellij.icons.AllIcons
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.GitNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ui.configuration.sdkComboBox
import com.intellij.openapi.startup.StartupManager
import com.intellij.ui.dsl.builder.*
import java.nio.file.Path

class KvueNewProjectWizard : GeneratorNewProjectWizard {
    override val id = "kvue"
    override val name = "KVUE"
    override val icon = AllIcons.Nodes.Module
    override val description = "使用 Kotlin 创建 Vue 应用，可选 Element Plus。"

    override fun createStep(context: WizardContext): NewProjectWizardStep =
        RootNewProjectWizardStep(context)
            .nextStep { NewProjectWizardBaseStep(it).apply { defaultName = "kvue-app" } }
            .nextStep(::GitNewProjectWizardStep)
            .nextStep(::KvueProjectWizardStep)
}

internal class KvueProjectWizardStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {
    val packageNameProperty = propertyGraph.property("app")
    val versionProperty = propertyGraph.property("0.1.0-SNAPSHOT")
    val starterProperty = propertyGraph.property(KvueStarter.KVUI)
    val repositoryProperty = propertyGraph.property(KvueRepository.GITHUB)
    val localRepositoryProperty = propertyGraph.property("")
    val sdkProperty = propertyGraph.property<Sdk?>(
        context.projectJdk?.takeIf(::supportedSdk)
            ?: ProjectJdkTable.getInstance().allJdks.firstOrNull(::supportedSdk),
    )

    override fun setupUI(builder: Panel) {
        with(builder) {
            row("包名:") {
                textField().bindText(packageNameProperty).columns(COLUMNS_MEDIUM)
                    .validationOnInput { KvueProjectTemplate.packageError(it.text)?.let(::error) }
                    .validationOnApply { KvueProjectTemplate.packageError(it.text)?.let(::error) }
            }
            row("模板:") {
                comboBox(KvueStarter.entries).bindItem({ starterProperty.get() }, { starterProperty.set(it ?: KvueStarter.KVUI) })
            }
            row("JDK（21+）:") {
                sdkComboBox(context, sdkProperty, "KVUE", sdkTypeFilter = { it is JavaSdk }, sdkFilter = ::supportedSdk)
                    .validationOnApply { if (sdkProperty.get()?.let(::supportedSdk) == true) null else error("请选择 JDK 21 或更高版本。") }
            }
            row("KVUE 版本:") {
                textField().bindText(versionProperty).columns(COLUMNS_MEDIUM)
                    .validationOnInput { KvueProjectTemplate.versionError(it.text)?.let(::error) }
                    .validationOnApply { KvueProjectTemplate.versionError(it.text)?.let(::error) }
            }
            row("依赖仓库:") {
                comboBox(KvueRepository.entries)
                    .bindItem({ repositoryProperty.get() }, { repositoryProperty.set(it ?: KvueRepository.GITHUB) })
            }
            val localRow = row("仓库目录:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor(), context.project)
                    .bindText(localRepositoryProperty).align(AlignX.FILL)
                    .validationOnInput {
                        if (repositoryProperty.get() == KvueRepository.LOCAL)
                            KvueProjectTemplate.repositoryError(it.text)?.let(::error) else null
                    }
                    .validationOnApply {
                        if (repositoryProperty.get() == KvueRepository.LOCAL)
                            KvueProjectTemplate.repositoryError(it.text)?.let(::error) else null
                    }
            }.visible(repositoryProperty.get() == KvueRepository.LOCAL)
            val githubRow = row {
                comment("GitHub Packages 需要读取权限。请设置 GITHUB_ACTOR / GITHUB_TOKEN，" +
                    "或在用户的 ~/.gradle/gradle.properties 中配置 kvue.github.user / kvue.github.token。")
            }.visible(repositoryProperty.get() == KvueRepository.GITHUB)
            repositoryProperty.afterChange {
                localRow.visible(it == KvueRepository.LOCAL)
                githubRow.visible(it == KvueRepository.GITHUB)
            }
            row {
                comment("创建后自动同步 Gradle，并添加“KVUE 开发服务器”和“KVUE 生产打包”运行配置。")
            }
        }
    }

    override fun setupProject(project: Project) {
        val base = requireNotNull(baseData)
        val sdk = requireNotNull(sdkProperty.get()) { "请选择 JDK 21 或更高版本。" }
        require(supportedSdk(sdk)) { "KVUE 项目需要 JDK 21 或更高版本。" }
        val root = Path.of(base.path).resolve(base.name).toAbsolutePath().normalize()
        val entry = KvueProjectTemplate.generate(root, KvueProjectOptions(
            base.name, packageNameProperty.get(), versionProperty.get(), starterProperty.get(),
            repositoryProperty.get(), localRepositoryProperty.get(),
        ))
        KvueCreatedProjectService.configure(project, root, sdk)
        StartupManager.getInstance(project).runAfterOpened {
            if (!project.isDisposed) project.service<KvueCreatedProjectService>().openAndSync(root, entry)
        }
    }

    private fun supportedSdk(sdk: Sdk): Boolean =
        sdk.sdkType is JavaSdk && JavaSdk.getInstance().getVersion(sdk)?.isAtLeast(JavaSdkVersion.JDK_21) == true
}
