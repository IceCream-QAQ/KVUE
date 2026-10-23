import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec

plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("jvm") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    js {
        binaries.executable()
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
    }

    sourceSets {
        jsMain {
            kotlin.srcDir("src/main/kotlin")
            kotlin.srcDir(layout.buildDirectory.dir("generated/kvue/js-components/kotlin"))
            resources.srcDir("src/main/resources")
            dependencies {
                implementation(npm("vue", "3.5.44"))
                implementation(npm("element-plus", "2.14.7"))
                implementation("org.jetbrains.kotlin:kotlin-dom-api-compat:2.4.21")
                implementation("org.jetbrains.kotlinx:kotlinx-html-js:0.12.0")
                implementation(devNpm("typescript", "6.0.3"))
            }
        }
        jsTest {
            kotlin.srcDir("src/test/kotlin")
            kotlin.srcDir(layout.buildDirectory.dir("generated/kvue/js-test-components/kotlin"))
            resources.srcDir("src/test/resources")
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

dependencies {
    add("kspJs", project(":kvue-ksp"))
    add("kspJsTest", project(":kvue-ksp"))
}

val nodeJs = extensions.getByType<NodeJsEnvSpec>()

fun registerJsComponentGeneration(taskName: String, configuration: String, output: String) =
    tasks.register<Exec>(taskName) {
        group = "code generation"
        description = "Generate Kotlin component schemas from TypeScript declarations or JS/TS source."
        dependsOn("kotlinNpmInstall", "kotlinStoreYarnLock")
        val configurationFile = layout.projectDirectory.file(configuration).asFile
        inputs.file(configurationFile)
        inputs.file("tools/js-components/generate.cjs")
        inputs.file("tools/js-components/source-analysis.cjs")
        inputs.file("kotlin-js-store/yarn.lock")
        inputs.files(fileTree(layout.buildDirectory.dir("js/node_modules")) {
            include("**/*.ts", "**/*.mts", "**/*.cts", "**/*.tsx", "**/*.js", "**/*.mjs", "**/*.cjs", "**/*.jsx", "**/package.json")
        })
        inputs.files(providers.provider {
            val data = JsonSlurper().parse(configurationFile) as Map<*, *>
            val entries = (data["libraries"] as List<*>).flatMap { library ->
                listOfNotNull((library as Map<*, *>)["declarations"] as? String, library["source"] as? String)
            }.map { configurationFile.parentFile.resolve(it) }
            // Include imported local source files discovered on the previous run.
            // Package files are also covered by the node_modules tree above.
            val reportFile = layout.buildDirectory.file("$output/report.json").get().asFile
            val sources = if (reportFile.isFile) {
                val report = JsonSlurper().parse(reportFile) as Map<*, *>
                (report["sources"] as? List<*>)?.map { file(it as String) } ?: emptyList()
            } else emptyList()
            entries + sources
        })
        outputs.dir(layout.buildDirectory.dir(output))
        doFirst {
            commandLine(
                nodeJs.executable.get(), "tools/js-components/generate.cjs",
                "--config", configuration,
                "--node-modules", layout.buildDirectory.dir("js/node_modules").get().asFile.absolutePath,
                "--out", layout.buildDirectory.dir(output).get().asFile.absolutePath,
            )
        }
    }

val generateJsComponents = registerJsComponentGeneration(
    "generateJsComponents", "kvue.components.json", "generated/kvue/js-components",
)
val generateJsTestComponents = registerJsComponentGeneration(
    "generateJsTestComponents", "tools/js-components/fixtures/components.json", "generated/kvue/js-test-components",
)

tasks.matching { it.name == "kspKotlinJs" }.configureEach { dependsOn(generateJsComponents) }
tasks.matching { it.name == "kspTestKotlinJs" }.configureEach { dependsOn(generateJsTestComponents) }

val testJsComponentImporter = tasks.register<Exec>("testJsComponentImporter") {
    group = "verification"
    dependsOn("kotlinNpmInstall")
    environment("KVUE_NODE_MODULES", layout.buildDirectory.dir("js/node_modules").get().asFile.absolutePath)
    doFirst { commandLine(nodeJs.executable.get(), "--test", "tools/js-components/generate.test.cjs") }
}
tasks.named("check") { dependsOn(testJsComponentImporter) }
