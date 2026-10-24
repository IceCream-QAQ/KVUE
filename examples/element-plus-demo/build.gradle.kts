plugins {
    kotlin("multiplatform") version "2.4.21"
    id("com.google.devtools.ksp") version "2.3.12"
    id("org.kvue") version "0.1.0-SNAPSHOT"
}

repositories {
    maven { url = uri("../../.gradle/kvue-demo-repository") }
    mavenCentral()
}

// This example consumes locally published development snapshots.
configurations.configureEach { resolutionStrategy.cacheChangingModulesFor(0, "seconds") }

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
            implementation("org.kvue:kvui:0.1.0-SNAPSHOT")
            implementation(npm("element-plus", "2.14.7"))
        }
    }
}

kvue {
    processTests.set(false)
    // IDEA 插件会注入关闭生成源暴露的默认值；这里保留自动选择。
    // exposeGeneratedSources.set(true) // 显式设置时按用户选择执行。
}
