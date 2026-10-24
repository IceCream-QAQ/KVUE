plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "org.kvue"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":kvue-codegen-core"))
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.12")
    implementation("com.squareup:kotlinpoet-ksp:2.4.0")
}

publishing { publications { create<MavenPublication>("maven") { from(components["java"]) } } }
