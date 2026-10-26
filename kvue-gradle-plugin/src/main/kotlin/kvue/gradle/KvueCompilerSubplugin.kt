package kvue.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/** Adds call-site binding and inline slot transforms to the Kotlin compiler, independently of KSP. */
class KvueCompilerSubplugin : KotlinCompilerPluginSupportPlugin {
    private lateinit var project: Project
    override fun apply(target: Project) { project = target }
    private val extension get() = project.extensions.getByType(KvueExtension::class.java)

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        extension.compilerPluginEnabled.get() && kotlinCompilation.target.name == extension.targetName.get() &&
            kotlinCompilation.platformType.name == "js"

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        extension.compilerProject.orNull?.let { localProject ->
            project.configurations.matching { it.name.startsWith("kotlinCompilerPluginClasspath") }.configureEach { configuration ->
                configuration.resolutionStrategy.dependencySubstitution { substitutions ->
                    substitutions.substitute(substitutions.module("org.kvue:kvue-compiler-plugin"))
                        .using(substitutions.project(localProject))
                }
            }
        }
        return project.provider { emptyList() }
    }

    override fun getCompilerPluginId() = "org.kvue.bindings"
    override fun getPluginArtifact() = SubpluginArtifact("org.kvue", "kvue-compiler-plugin", extension.version.get())
}
