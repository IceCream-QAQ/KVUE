
pluginManagement { includeBuild("kvue-gradle-plugin") }

rootProject.name = "KVUE"

include(":kvue-ksp")
include(":kvue-codegen-core", ":kvue-runtime")

