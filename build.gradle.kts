plugins {
    kotlin("multiplatform") version "2.4.21"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    js {
        compilerOptions {
            // Component discovers delegated state and props by their generated field names.
            freeCompilerArgs.add("-Xir-minimized-member-names=false")
        }
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
                implementation(npm("vue", "3.5.44"))
                implementation("org.jetbrains.kotlinx:kotlinx-html-js:0.12.0")
            }
        }
        jsTest {
            kotlin.srcDir("src/test/kotlin")
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
