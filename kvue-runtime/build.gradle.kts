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
            dependencies { api(npm("vue", "3.5.44")) }
        }
        jsTest.dependencies { implementation(kotlin("test")) }
    }
}

kvue {
    addRuntimeDependency.set(false)
    // Only test fixtures need generated component declarations.
    addProcessorDependency.set(false)
    compilerProject.set(":kvue-compiler-plugin")
}
dependencies { add("kspJsTest", project(":kvue-ksp")) }
