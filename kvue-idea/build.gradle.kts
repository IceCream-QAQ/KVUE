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
val analysisJsDom = configurations.create("analysisJsDom") { isTransitive = false }
dependencies {
    add(analysisStdlib.name, "org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    add(analysisJsStdlib.name, "org.jetbrains.kotlin:kotlin-stdlib-js:2.4.21@klib")
    add(analysisJsDom.name, "org.jetbrains.kotlin:kotlin-dom-api-compat:2.4.21@klib")
    implementation(project(":kvue-codegen-core")) { exclude(group = "org.jetbrains.kotlin") }
    intellijPlatform {
        val localIde = providers.gradleProperty("ideaHome")
        if (localIde.isPresent) local(localIde.get()) else intellijIdea("2026.2.3")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")
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
tasks.named<JavaExec>("runIde") {
    // The sandbox keeps its Gradle model between runs, while our locally published SNAPSHOT changes.
    systemProperty("kvue.development.refreshGradleOnStartup", "true")
}
// Exercise the published binary shape rather than test doubles for ref inheritance and DSL receivers.
val runtimeKlib = layout.projectDirectory.file("../kvue-runtime/build/libs/kvue-runtime-js-0.1.0-SNAPSHOT.klib")
val prepareRuntimeKlib = tasks.register<Exec>("prepareRuntimeKlib") {
    workingDir(layout.projectDirectory.dir(".."))
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "./gradlew",
        "--no-daemon", "--console=plain", ":kvue-runtime:jsJar")
}
tasks.test {
    dependsOn(prepareRuntimeKlib)
    inputs.file(runtimeKlib).withPropertyName("kvueRuntimeKlib")
    maxHeapSize = "2g"
    systemProperty("idea.kotlin.plugin.use.k2", "true")
    systemProperty("kvue.test.stdlib", analysisStdlib.singleFile.absolutePath)
    systemProperty("kvue.test.js.stdlib", analysisJsStdlib.singleFile.absolutePath)
    systemProperty("kvue.test.js.dom", analysisJsDom.singleFile.absolutePath)
    systemProperty("kvue.test.runtime.klib", runtimeKlib.asFile.absolutePath)
}
