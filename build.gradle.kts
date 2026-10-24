import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec

plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("jvm") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12"
    id("org.kvue")
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
            resources.srcDir("src/main/resources")
            dependencies {
                implementation(project(":kvui"))
                implementation(npm("element-plus", "2.14.7"))
                implementation("org.jetbrains.kotlin:kotlin-dom-api-compat:2.4.21")
                implementation("org.jetbrains.kotlinx:kotlinx-html-js:0.12.0")
            }
        }
        jsTest {
            kotlin.srcDir("src/test/kotlin")
            resources.srcDir("src/test/resources")
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

kvue {
    runtimeProject.set(":kvue-runtime")
    processorProject.set(":kvue-ksp")
    compilerProject.set(":kvue-compiler-plugin")
    testComponentsFile.set(layout.projectDirectory.file("tools/js-components/fixtures/components.json"))
}

val nodeJs = extensions.getByType<NodeJsEnvSpec>()

tasks.register("generateJsComponents") { dependsOn("generateKvueJsComponents") }
tasks.register("generateJsTestComponents") { dependsOn("generateKvueJsTestComponents") }

val testJsComponentImporter = tasks.register<Exec>("testJsComponentImporter") {
    group = "verification"
    dependsOn("kotlinNpmInstall")
    environment("KVUE_NODE_MODULES", layout.buildDirectory.dir("js/node_modules").get().asFile.absolutePath)
    doFirst { commandLine(nodeJs.executable.get(), "--test", "tools/js-components/generate.test.cjs") }
}
val checkKvuiElements = tasks.register<Exec>("checkKvuiElements") {
    group = "verification"
    dependsOn("kotlinNodeJsSetup")
    doFirst { commandLine(nodeJs.executable.get(), "tools/kvui/generate.cjs", "--check") }
}
tasks.named("check") { dependsOn(testJsComponentImporter, checkKvuiElements) }
