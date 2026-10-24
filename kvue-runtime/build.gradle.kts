plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "org.kvue"
version = "0.1.0-SNAPSHOT"
repositories { mavenCentral() }
kotlin {
    js { browser() }
    sourceSets {
        jsMain {
            kotlin.srcDir("src/main/kotlin")
            dependencies { api(npm("vue", "3.5.44")) }
        }
    }
}
