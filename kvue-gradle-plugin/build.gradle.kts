plugins {
    kotlin("jvm") version "2.4.21"
    `java-gradle-plugin`
    `maven-publish`
}
group = "org.kvue"
version = "0.1.0-SNAPSHOT"
repositories { mavenCentral(); gradlePluginPortal() }
kotlin { jvmToolchain(21) }
dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    compileOnly("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.12")
    compileOnly(localGroovy())
    testImplementation(kotlin("test-junit"))
    testImplementation(gradleTestKit())
}
gradlePlugin {
    plugins {
        create("kvue") {
            id = "org.kvue"
            implementationClass = "kvue.gradle.KvuePlugin"
            displayName = "KVUE component generation"
            description = "Configures KVUE runtime, KSP, and third-party JS/TS component imports."
        }
    }
}
tasks.processResources {
    from("../tools/js-components") { include("*.cjs"); exclude("*.test.cjs"); into("js-importer") }
}
publishing { repositories { maven { name = "test"; url = layout.buildDirectory.dir("test-repository").get().asFile.toURI() } } }
tasks.test {
    dependsOn("publishAllPublicationsToTestRepository")
    systemProperty("kvue.plugin.test.repository", layout.buildDirectory.dir("test-repository").get().asFile.toURI().toString())
}
