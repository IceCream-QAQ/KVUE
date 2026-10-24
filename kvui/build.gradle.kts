import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec

plugins {
    kotlin("multiplatform")
    id("com.google.devtools.ksp")
    id("org.kvue")
    `maven-publish`
}

group = "org.kvue"
version = "0.1.0-SNAPSHOT"
repositories { mavenCentral() }

kotlin {
    js {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                    webpackConfig.configDirectory = rootProject.file("gradle/webpack")
                }
            }
        }
    }
    sourceSets {
        jsMain {
            kotlin.srcDir("src/main/kotlin")
            dependencies {
                api(project(":kvue-runtime"))
                api("org.jetbrains.kotlin:kotlin-dom-api-compat:2.4.21")
            }
        }
        jsTest.dependencies { implementation(kotlin("test")) }
    }
}

kvue {
    addRuntimeDependency.set(false)
    addProcessorDependency.set(false)
    compilerProject.set(":kvue-compiler-plugin")
}
dependencies { add("kspJsTest", project(":kvue-ksp")) }

val nodeJs = rootProject.extensions.getByType<NodeJsEnvSpec>()
val checkKvuiElements = tasks.register<Exec>("checkKvuiElements") {
    group = "verification"
    dependsOn(rootProject.tasks.named("kotlinNodeJsSetup"))
    doFirst { commandLine(nodeJs.executable.get(), layout.projectDirectory.file("tools/generate.cjs").asFile.absolutePath, "--check") }
}
tasks.named("check") { dependsOn(checkKvuiElements) }
