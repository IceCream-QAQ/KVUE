package kvue.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertTrue

class KvuePluginTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun configuresDownstreamDependenciesSourcesAndTaskOrder() {
        val project = temporary.newFolder("consumer")
        project.resolve("settings.gradle.kts").writeText(settings("consumer"))
        project.resolve("kvue.components.json").writeText("""{"libraries":[{"module":"widgets","package":"widgets","exports":"*"}]}""")
        project.resolve("build.gradle.kts").writeText("""
            import org.gradle.api.tasks.TaskProvider
            import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
            plugins {
                kotlin("multiplatform") version "2.4.21"
                id("com.google.devtools.ksp") version "2.3.12"
                id("org.kvue") version "0.1.0-SNAPSHOT"
            }
            repositories { mavenCentral() }
            kotlin { js { browser() } }
            kvue { version.set("0.1.0-SNAPSHOT") }
            tasks.register("verifyWiring") {
                doLast {
                    check(configurations.getByName("jsMainApi").dependencies.any { it.group == "org.kvue" && it.name == "kvue-runtime" })
                    check(configurations.getByName("kspJs").dependencies.any { it.group == "org.kvue" && it.name == "kvue-ksp" })
                    check(configurations.getByName("kspJsTest").dependencies.any { it.name == "kvue-ksp" })
                    val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
                    check(kotlin.sourceSets.getByName("jsMain").kotlin.srcDirs.any { it.path.replace('\\', '/').endsWith("generated/kvue/js-components/kotlin") })
                    val generation = tasks.getByName("generateKvueJsComponents")
                    check(tasks.getByName("kspKotlinJs").dependsOn.filterIsInstance<TaskProvider<*>>().any { it.name == generation.name })
                    check(tasks.findByName("generateKvueJsTestComponents") == null)
                    println("KVUE downstream wiring verified")
                }
            }
        """.trimIndent())
        val result = GradleRunner.create().withProjectDir(project)
            .withArguments("verifyWiring", "--stacktrace", "--console=plain").build()
        assertTrue(result.output.contains("KVUE downstream wiring verified"))
    }

    @Test fun reportsMissingRequiredPluginsWithoutApplyingThemImplicitly() {
        val project = temporary.newFolder("missing-plugins")
        project.resolve("settings.gradle.kts").writeText(settings("missing-plugins"))
        project.resolve("build.gradle.kts").writeText("plugins { id(\"org.kvue\") version \"0.1.0-SNAPSHOT\" }")
        val result = GradleRunner.create().withProjectDir(project).withArguments("help", "--stacktrace").buildAndFail()
        assertTrue(result.output.contains("requires the Kotlin Multiplatform and official com.google.devtools.ksp plugins"), result.output)
    }

    private fun settings(name: String) = """
        pluginManagement {
            repositories {
                maven { url = uri("${System.getProperty("kvue.plugin.test.repository")}") }
                gradlePluginPortal()
                mavenCentral()
            }
        }
        rootProject.name = "$name"
    """.trimIndent()
}
