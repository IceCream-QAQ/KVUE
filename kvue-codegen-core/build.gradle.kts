plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

group = "org.kvue"
version = "0.1.0-SNAPSHOT"
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
dependencies {
    api("com.squareup:kotlinpoet:2.4.0")
    compileOnly(kotlin("stdlib"))
    testImplementation(kotlin("test-junit"))
}
publishing { publications { create<MavenPublication>("maven") { from(components["java"]) } } }
