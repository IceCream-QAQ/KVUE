
pluginManagement { includeBuild("kvue-gradle-plugin") }

rootProject.name = "KVUE"

include(":kvue-ksp")
include(":kvue-codegen-core", ":kvue-runtime")
include(":kvue-compiler-plugin")
include(":kvui")

