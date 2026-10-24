package kvue.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import com.google.devtools.ksp.gradle.KspAATask
import com.google.devtools.ksp.gradle.KspExtension
import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompileTool

class KvuePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("kvue", KvueExtension::class.java)
        val defaultConfiguration = project.layout.projectDirectory.file("kvue.components.json")
        if (defaultConfiguration.asFile.isFile) extension.componentsFile.convention(defaultConfiguration)
        project.afterEvaluate {
            if (!project.pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform") ||
                !project.pluginManager.hasPlugin("com.google.devtools.ksp")) {
                throw GradleException("KVUE requires the Kotlin Multiplatform and official com.google.devtools.ksp plugins to be applied explicitly.")
            }
            KotlinIntegration.configure(project, extension)
        }
    }
}

// Keep optional Kotlin API types out of the Plugin class's reflected method signatures.
internal object KotlinIntegration {
    fun configure(project: Project, extension: KvueExtension) {
        val expose = project.providers.gradleProperty("kvue.exposeGeneratedSources")
            .map { parseExposure(it, "kvue.exposeGeneratedSources") }
            .orElse(extension.exposeGeneratedSources).get()
        extension.exposeGeneratedSources.set(expose)
        extension.exposeGeneratedSources.disallowChanges()
        project.extensions.getByType(KspExtension::class.java).arg("kvue.outputSubdirectory", "__kvue__")
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        val target = kotlin.targets.findByName(extension.targetName.get())
            ?: throw GradleException("KVUE target '${extension.targetName.get()}' does not exist.")
        require(target.platformType.name == "js") { "KVUE requires a Kotlin/JS target." }
        val main = target.compilations.getByName("main")
        if (extension.addRuntimeDependency.get()) {
            project.dependencies.add(main.defaultSourceSet.apiConfigurationName,
                dependency(project, extension.runtimeProject.orNull, "kvue-runtime", extension.version.get()))
        }
        for (compilation in target.compilations.filter { it.name == "main" || (it.name == "test" && extension.processTests.get()) }) {
            val isTest = compilation.name == "test"
            val processorConfiguration = "ksp" + target.name.replaceFirstChar(Char::uppercaseChar) + if (isTest) "Test" else ""
            if (extension.addProcessorDependency.get()) {
                if (project.configurations.findByName(processorConfiguration) == null) throw GradleException("KSP did not create $processorConfiguration.")
                project.dependencies.add(processorConfiguration,
                    dependency(project, extension.processorProject.orNull, "kvue-ksp", extension.version.get()))
            }
            val configuration = if (isTest) extension.testComponentsFile else extension.componentsFile
            if (configuration.isPresent) configureImporter(project, compilation, configuration, isTest, extension)
            if (!expose) {
                // KSP registers its roots during Kotlin's compilation finalization. Run after that registration.
                project.afterEvaluate { BuildOnlySources.configure(project, compilation) }
            }
        }
    }

    private fun dependency(project: Project, localProject: String?, artifact: String, version: String): Any =
        if (localProject == null) "org.kvue:$artifact:$version" else project.dependencies.project(mapOf("path" to localProject))

    private fun configureImporter(project: Project, compilation: KotlinCompilation<*>, configuration: RegularFileProperty, test: Boolean, extension: KvueExtension) {
        compilation.defaultSourceSet.dependencies { implementation(devNpm("typescript", extension.typescriptVersion.get())) }
        val node = project.rootProject.extensions.getByType(NodeJsEnvSpec::class.java)
        val nodeModulesDirectory = project.rootProject.layout.buildDirectory.dir("js/node_modules")
        val generate = project.tasks.register(if (test) "generateKvueJsTestComponents" else "generateKvueJsComponents", GenerateKvueJsComponents::class.java) {
            it.group = "code generation"
            it.description = "Generate KVUE schemas from third-party TypeScript declarations or JavaScript source."
            it.configurationFile.set(configuration)
            it.nodeModules.set(nodeModulesDirectory)
            it.nodeExecutable.set(node.executable)
            it.outputDirectory.set(project.layout.buildDirectory.dir(if (test) "generated/kvue/js-test-components" else "generated/kvue/js-components"))
            it.packageInputs.from(project.fileTree(nodeModulesDirectory) { tree ->
                tree.include("**/*.ts", "**/*.mts", "**/*.cts", "**/*.tsx", "**/*.js", "**/*.mjs", "**/*.cjs", "**/*.jsx", "**/package.json")
            })
            it.dependsOn(project.rootProject.tasks.named("kotlinNpmInstall"), project.rootProject.tasks.named("kotlinStoreYarnLock"))
        }
        val schemas = generate.flatMap { it.outputDirectory.dir("kotlin") }
        if (extension.exposeGeneratedSources.get()) {
            compilation.defaultSourceSet.kotlin.srcDir(schemas)
        } else {
            compilation.compileTaskProvider.configure { (it as AbstractKotlinCompileTool<*>).source(schemas); it.dependsOn(generate) }
            project.tasks.withType(KspAATask::class.java).matching {
                it.name == compilation.compileTaskProvider.name.replaceFirst("compile", "ksp")
            }.configureEach { it.kspConfig.sourceRoots.from(project.files(schemas).asFileTree) }
        }
        val kspTask = compilation.compileTaskProvider.name.replaceFirst("compile", "ksp")
        project.tasks.matching { it.name == kspTask }.configureEach { it.dependsOn(generate) }
    }
}
