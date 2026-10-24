plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "org.kvue"
version = "0.1.0-SNAPSHOT"
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21")
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21")
    testImplementation(kotlin("test-junit"))
}
publishing { publications { create<MavenPublication>("maven") { from(components["java"]) } } }
tasks.test {
    dependsOn(tasks.jar)
    systemProperty("kvue.compiler.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
}
