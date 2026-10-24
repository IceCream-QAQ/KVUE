import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec

plugins {
    kotlin("multiplatform")
    id("com.google.devtools.ksp")
    id("org.kvue")
}

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
        jsTest {
            resources.srcDir("fixtures")
            resources.include("*.cjs", "*.mjs")
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":kvui"))
                implementation(npm("element-plus", "2.14.7"))
            }
        }
    }
}

kvue {
    runtimeProject.set(":kvue-runtime")
    addProcessorDependency.set(false)
    compilerProject.set(":kvue-compiler-plugin")
    testComponentsFile.set(layout.projectDirectory.file("fixtures/components.json"))
}
dependencies { add("kspJsTest", project(":kvue-ksp")) }

val nodeJs = rootProject.extensions.getByType<NodeJsEnvSpec>()
val testJsComponentImporter = tasks.register<Exec>("testJsComponentImporter") {
    group = "verification"
    dependsOn(rootProject.tasks.named("kotlinNpmInstall"))
    workingDir(projectDir)
    environment("KVUE_NODE_MODULES", rootProject.layout.buildDirectory.dir("js/node_modules").get().asFile.absolutePath)
    doFirst { commandLine(nodeJs.executable.get(), "--test", "generate.test.cjs") }
}
tasks.named("check") { dependsOn(testJsComponentImporter) }
