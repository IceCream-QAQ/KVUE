package kvue.gradle

import com.google.devtools.ksp.gradle.KspAATask
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompileTool

/** Keep KSP's tracked outputs intact; project only the owned Kotlin subtree into build-only inputs. */
internal object BuildOnlySources {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    fun configure(project: Project, compilation: KotlinCompilation<*>) {
        val taskName = compilation.compileTaskProvider.name.replaceFirst("compile", "ksp")
        val ksp = project.tasks.findByName(taskName) as? KspAATask ?: return
        val raw = ksp.kspConfig.kotlinOutputDir.get().asFile
        val output = project.layout.buildDirectory.dir("generated/kvue/build-only/${compilation.target.name}/${compilation.name}")
        val other = output.map { it.dir("other") }
        val owned = output.map { it.dir("kvue") }
        val prepare = project.tasks.register("prepareKvue${compilation.target.name.replaceFirstChar(Char::uppercaseChar)}${compilation.name.replaceFirstChar(Char::uppercaseChar)}Sources", Sync::class.java) {
            it.group = "code generation"
            it.description = "Separate KVUE build-only sources from other KSP processors' IDE-visible sources."
            it.dependsOn(ksp)
            it.into(output)
            it.from(raw.resolve("__kvue__")) { spec -> spec.into("kvue") }
            it.from(raw) { spec -> spec.exclude("__kvue__/**"); spec.into("other") }
            it.includeEmptyDirs = false
        }
        // Direct invocations of kspKotlinJs must also refresh the IDE-visible outputs of other processors.
        ksp.finalizedBy(prepare)
        // Gradle SourceDirectorySet has no removeSrcDir API. Snapshot only the sets containing this
        // KSP root after registration and preserve their producer dependencies before replacing paths.
        val producers = linkedSetOf<org.gradle.api.Task>()
        val sourceSet = compilation.defaultSourceSet
        for (sources in listOf(sourceSet.kotlin, sourceSet.generatedKotlin)) {
            if (raw !in sources.srcDirs) continue
            producers += sources.buildDependencies.getDependencies(null).filter { it != ksp }
            sources.setSrcDirs(sources.srcDirs.filter { it != raw })
        }
        // Use a plain directory here: attaching the Sync producer to this source set would make KSP
        // depend on its own output projection. The compiler dependency is connected explicitly below.
        sourceSet.kotlin.srcDir(other)
        project.extensions.getByType(KspExtension::class.java).excludedSources.from(other)
        ksp.dependsOn(producers)
        compilation.compileTaskProvider.configure {
            (it as AbstractKotlinCompileTool<*>).source(owned)
            it.dependsOn(prepare, producers)
        }
    }
}
