pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
rootProject.name = "kvue-idea"
include(":kvue-codegen-core")
project(":kvue-codegen-core").projectDir = file("../kvue-codegen-core")
