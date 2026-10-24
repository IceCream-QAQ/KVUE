plugins {
    kotlin("multiplatform") version "2.4.21"
    id("com.google.devtools.ksp") version "2.3.12"
    id("org.kvue") version "0.1.0-SNAPSHOT"
}

repositories {
    maven { url = uri("../../.gradle/kvue-demo-repository") }
    mavenCentral()
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "playground.js"
                cssSupport { enabled.set(true) }
            }
        }
        binaries.executable()
    }
    sourceSets {
        jsMain.dependencies {
            implementation(npm("element-plus", "2.14.7"))
            implementation("org.jetbrains.kotlin:kotlin-dom-api-compat:2.4.21")
        }
    }
}

kvue {
    processTests.set(false)
    // IDEA 插件会注入关闭生成源暴露的默认值；这里保留自动选择。
    // exposeGeneratedSources.set(true) // 显式设置时按用户选择执行。
}
