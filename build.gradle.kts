plugins {
    base
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("jvm") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("org.kvue") apply false
}

tasks.named("check") {
    group = "verification"
    description = "Run checks owned by the runtime, UI, generation, compiler, and importer modules."
    dependsOn(
        ":kvue-runtime:check",
        ":kvui:check",
        ":kvue-codegen-core:check",
        ":kvue-ksp:check",
        ":kvue-compiler-plugin:check",
        ":js-component-importer:check",
    )
}
