plugins {
    kotlin("js") version "2.0.20"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(npm("vue",">3.2.47"))
    implementation("org.jetbrains.kotlinx:kotlinx-html-js:0.9.0")
}

kotlin {
    js {
        binaries.executable()
        browser {

        }
    }
}