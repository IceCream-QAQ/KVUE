package kvue.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.*

class GeneratedSourcesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun explicitOverridesBeatEnvironmentDefaultsAndEnvironmentChangesAreObserved() {
        val project = consumer("precedence")
        fun verify(expected: Boolean, env: Map<String, String> = emptyMap(), vararg args: String) {
            val result = runner(project, env, "dumpRoots", *args).build()
            assertTrue(result.output.contains("exposure=$expected"), result.output)
            val roots = result.output.lineSequence().single { it.startsWith("roots=") }
            assertEquals(expected, "generated/ksp/js/jsMain/kotlin" in roots, roots)
            assertEquals(!expected, "generated/kvue/build-only/js/main/other" in roots, roots)
            assertFalse("generated/kvue/build-only/js/main/kvue" in roots, roots)
        }
        verify(true)
        verify(false, mapOf(DEFAULT_ENV to "false"))
        verify(true, mapOf(DEFAULT_ENV to "false", USER_ENV to "true"))
        verify(false, mapOf(DEFAULT_ENV to "true", USER_ENV to "false"))
        verify(true, mapOf(USER_ENV to "false"), "-PtestDslExposure=true")
        verify(false, mapOf(USER_ENV to "true"), "-PtestDslExposure=false")
        verify(false, mapOf(USER_ENV to "true"), "-PtestDslExposure=true", "-Pkvue.exposeGeneratedSources=false")
        verify(true, mapOf(DEFAULT_ENV to "false"), "-PtestDslExposure=false", "-Pkvue.exposeGeneratedSources=true")
        verify(true) // same project/daemon, without the earlier IDE environment
        val invalid = runner(project, emptyMap(), "help", "-Pkvue.exposeGeneratedSources=maybe").buildAndFail()
        assertTrue(invalid.output.contains("kvue.exposeGeneratedSources must be true or false"), invalid.output)
    }

    @Test fun buildOnlyModeKeepsOtherProcessorsVisibleAndCleansDeletedOutputs() {
        val project = consumer("build-only")
        val hidden = mapOf(DEFAULT_ENV to "false")
        val generated = runner(project, hidden, "kspKotlinJs").build()
        assertEquals(TaskOutcome.SUCCESS, generated.task(":prepareKvueJsMainSources")?.outcome)
        val first = runner(project, hidden, "compileKotlinJs", "dumpRoots", "--configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileKotlinJs")?.outcome)
        val raw = project.resolve("build/generated/ksp/js/jsMain/kotlin")
        val projection = project.resolve("build/generated/kvue/build-only/js/main")
        assertTrue(raw.resolve("__kvue__/owned/OwnedKVueGenerated.kt").isFile)
        assertTrue(projection.resolve("kvue/owned/OwnedKVueGenerated.kt").isFile)
        assertTrue(projection.resolve("other/other/OtherKVueGenerated.kt").isFile)
        assertFalse(projection.resolve("other/__kvue__").exists())
        val roots = first.output.lineSequence().single { it.startsWith("roots=") }
        assertTrue("build-only/js/main/other" in roots, roots)
        assertFalse("generated/ksp/js/jsMain/kotlin" in roots, roots)
        assertTrue("generated/custom" in roots, roots)

        val repeated = runner(project, hidden, "compileKotlinJs", "dumpRoots", "--configuration-cache").build()
        assertTrue(repeated.output.contains("Reusing configuration cache"), repeated.output)
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":kspKotlinJs")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":prepareKvueJsMainSources")?.outcome)
        // Turning exposure on recompiles through the original KSP directory; all processors still run.
        val visible = runner(project, emptyMap(), "compileKotlinJs", "dumpRoots", "--configuration-cache").build()
        assertTrue(visible.output.contains("exposure=true"), visible.output)
        assertNull(visible.task(":prepareKvueJsMainSources"))
        // Return to the hidden mode and delete a component. No stale copied declaration may survive.
        project.resolve("src/jsMain/kotlin/CreateOwned.kt").delete()
        project.resolve("src/jsMain/kotlin/Usage.kt").writeText("fun answer() = other.Answer().value + custom.Custom().value")
        runner(project, hidden, "compileKotlinJs", "dumpRoots", "--configuration-cache").build()
        assertFalse(projection.resolve("kvue/owned/OwnedKVueGenerated.kt").exists())
        assertFalse(raw.resolve("__kvue__/owned/OwnedKVueGenerated.kt").exists())
        assertTrue(projection.resolve("other/other/OtherKVueGenerated.kt").isFile)
    }

    @Test fun supportsKspGeneratedKotlinSourceDirectoryApi() {
        val project = consumer("isolated-codepath")
        val result = runner(project, mapOf(DEFAULT_ENV to "false"), "compileKotlinJs", "dumpRoots",
            "-Pksp.project.isolation.enabled=true").build()
        assertTrue(result.output.contains("exposure=false"), result.output)
        val roots = result.output.lineSequence().single { it.startsWith("roots=") }
        assertFalse("generated/ksp/js/jsMain/kotlin" in roots, roots)
        assertTrue("build-only/js/main/other" in roots, roots)
    }

    private fun consumer(name: String): File {
        val project = temporary.newFolder(name)
        project.resolve("settings.gradle.kts").writeText("""
            pluginManagement {
                repositories {
                    maven { url = uri("${System.getProperty("kvue.plugin.test.repository")}") }
                    gradlePluginPortal(); mavenCentral()
                }
            }
            rootProject.name = "$name"
            include(":processor")
        """.trimIndent())
        project.resolve("build.gradle.kts").writeText("""
            import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
            plugins {
                kotlin("multiplatform") version "2.4.21"
                id("com.google.devtools.ksp") version "2.3.12"
                id("org.kvue") version "0.1.0-SNAPSHOT"
            }
            repositories { mavenCentral() }
            kotlin { js { browser() } }
            kvue {
                addRuntimeDependency.set(false)
                addProcessorDependency.set(false)
                processTests.set(false)
                providers.gradleProperty("testDslExposure").orNull?.let { exposeGeneratedSources.set(it.toBooleanStrict()) }
            }
            dependencies { add("kspJs", project(":processor")) }
            val customOutput = layout.buildDirectory.dir("generated/custom")
            val generateCustom = tasks.register("generateCustom") {
                val destination = customOutput
                outputs.dir(destination)
                doLast {
                    destination.get().asFile.apply { mkdirs() }.resolve("Custom.kt")
                        .writeText("package custom; class Custom { val value = 5 }")
                }
            }
            kotlin.sourceSets.getByName("jsMain").kotlin.srcDir(generateCustom.map { customOutput.get() })
            val dumpRoots = tasks.register("dumpRoots")
            gradle.projectsEvaluated {
                val sources = kotlin.sourceSets.getByName("jsMain")
                val roots = (sources.kotlin.srcDirs + sources.generatedKotlin.srcDirs).joinToString(";") { it.invariantSeparatorsPath }
                val exposed = kvue.exposeGeneratedSources.get()
                dumpRoots.configure { doLast { println("exposure=" + exposed); println("roots=" + roots) } }
            }
        """.trimIndent())
        project.resolve("src/jsMain/kotlin").mkdirs()
        project.resolve("src/jsMain/kotlin/Usage.kt").writeText("fun answer() = owned.Answer().value + other.Answer().value + custom.Custom().value")
        project.resolve("src/jsMain/kotlin/CreateOwned.kt").writeText("class CreateOwned")
        project.resolve("processor/src/main/java/example").mkdirs()
        project.resolve("processor/build.gradle.kts").writeText("""
            plugins { java }
            repositories { mavenCentral() }
            dependencies { implementation("com.google.devtools.ksp:symbol-processing-api:2.3.12") }
        """.trimIndent())
        project.resolve("processor/src/main/java/example/MixedProcessor.java").writeText("""
            package example;
            import com.google.devtools.ksp.processing.*;
            import com.google.devtools.ksp.symbol.*;
            import java.io.*;
            import java.nio.charset.StandardCharsets;
            import java.util.*;
            public final class MixedProcessor implements SymbolProcessorProvider {
                public SymbolProcessor create(SymbolProcessorEnvironment env) {
                    return new SymbolProcessor() {
                        boolean done;
                        public List<KSAnnotated> process(Resolver resolver) {
                            if (done) return Collections.emptyList();
                            done = true;
                            if (resolver.getClassDeclarationByName(resolver.getKSNameFromString("custom.Custom")) == null)
                                throw new IllegalStateException("The other source generator must run before KSP");
                            List<KSFile> files = new ArrayList<>();
                            resolver.getAllFiles().iterator().forEachRemaining(files::add);
                            Dependencies deps = new Dependencies(true, files.toArray(new KSFile[0]));
                            try {
                                if (files.stream().anyMatch(f -> f.getFileName().equals("CreateOwned.kt"))) {
                                    String prefix = env.getOptions().get("kvue.outputSubdirectory");
                                    write(env.getCodeGenerator().createNewFileByPath(deps, prefix + "/owned/OwnedKVueGenerated", "kt"),
                                        "package owned; class Answer { val value = 20 }");
                                }
                                // Deliberately shares the suffix; only the owned subtree may be hidden.
                                write(env.getCodeGenerator().createNewFile(deps, "other", "OtherKVueGenerated", "kt"),
                                    "package other; class Answer { val value = 17 }");
                            } catch (IOException e) { throw new RuntimeException(e); }
                            return Collections.emptyList();
                        }
                        public void finish() {}
                        public void onError() {}
                    };
                }
                static void write(OutputStream stream, String text) throws IOException {
                    try (OutputStream output = stream) { output.write(text.getBytes(StandardCharsets.UTF_8)); }
                }
            }
        """.trimIndent())
        project.resolve("processor/src/main/resources/META-INF/services").mkdirs()
        project.resolve("processor/src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider")
            .writeText("example.MixedProcessor")
        return project
    }

    private fun runner(project: File, env: Map<String, String>, vararg args: String): GradleRunner = GradleRunner.create()
        .withProjectDir(project)
        .withEnvironment(System.getenv().filterKeys { it != USER_ENV && it != DEFAULT_ENV } + env)
        .withArguments(*args, "--console=plain", "--stacktrace")

    companion object {
        const val USER_ENV = "KVUE_EXPOSE_GENERATED_SOURCES"
        const val DEFAULT_ENV = "KVUE_EXPOSE_GENERATED_SOURCES_DEFAULT"
    }
}
