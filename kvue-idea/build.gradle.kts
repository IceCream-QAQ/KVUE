import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "org.kvue"
version = "0.1.0-SNAPSHOT"
repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}
kotlin { jvmToolchain(25) }
val analysisStdlib = configurations.create("analysisStdlib") { isTransitive = false }
val analysisJsStdlib = configurations.create("analysisJsStdlib") { isTransitive = false }
dependencies {
    add(analysisStdlib.name, "org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    add(analysisJsStdlib.name, "org.jetbrains.kotlin:kotlin-stdlib-js:2.4.21@klib")
    implementation(project(":kvue-codegen-core")) { exclude(group = "org.jetbrains.kotlin") }
    intellijPlatform {
        val localIde = providers.gradleProperty("ideaHome")
        if (localIde.isPresent) local(localIde.get()) else intellijIdea("2026.2.3")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}
intellijPlatform {
    pluginConfiguration {
        name = "KVUE"
        ideaVersion { sinceBuild = "262"; untilBuild = "262.*" }
    }
    buildSearchableOptions = false
    instrumentCode = false
}
tasks.processResources {
    from("../tools/js-components") { include("*.cjs"); exclude("*.test.cjs"); into("js-importer") }
}
tasks.test {
    maxHeapSize = "2g"
    systemProperty("idea.kotlin.plugin.use.k2", "true")
    systemProperty("kvue.test.stdlib", analysisStdlib.singleFile.absolutePath)
    systemProperty("kvue.test.js.stdlib", analysisJsStdlib.singleFile.absolutePath)
}
