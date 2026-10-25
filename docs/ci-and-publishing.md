# 持续集成与包发布

[构建与发布工作流](../.github/workflows/ci.yml) 在推送到 `master`、向 `master` 提交拉取请求，或从 GitHub Actions 页面手动运行时执行。

## 构建流程

| 作业 | 执行内容 |
| --- | --- |
| 检查与 Maven 打包 | 使用 JDK 21 执行主工程和 Gradle 插件检查，再将所有 Maven 发布产物写入工作区临时仓库 |
| IDEA 插件 | 安装 JDK 21 与 JDK 25，下载构建配置指定的 IDEA，执行插件测试、打包和结构检查，上传安装包 |
| 发布 GitHub Packages | 前两项全部成功后，将当前版本的 Maven 包上传到本仓库的 GitHub Packages |

拉取请求只检查和生成构建产物。推送到 `master`，或手动选择 `master` 运行时，才会执行远端发布；手动运行其他分支不会发布包。

检查阶段显式使用 `kvue.exposeGeneratedSources=false`，覆盖 IDEA 插件使用的构建模式；生成 Maven 源码包时显式设为 `true`。
发布作业失败会使本次工作流失败，已经生成的 IDEA 插件产物仍可下载。多模块 Maven 发布不是原子操作，失败后可能已有部分包上传；修正问题后可重新运行发布作业。

## 下载 IDEA 插件

打开仓库的 **Actions → 构建与发布 → 对应运行记录**，在构建摘要的插件下载链接或 **Artifacts** 中下载 `kvue-idea-<提交号>`。

解压下载的构建产物后，将里面的 `kvue-idea-0.1.0-SNAPSHOT.zip` 通过 IDEA 插件设置的「从磁盘安装插件」安装。插件安装包保留 30 天，测试报告保留 14 天。这里分发的是工作流构建产物，不向 JetBrains 插件市场发布。

## Maven 发布范围与认证

当前版本为 `0.1.0-SNAPSHOT`，沿用各模块的版本声明，不根据构建号或标签改写版本。GitHub Packages 支持 Maven 快照版本，后续构建会继续更新该开发版本；使用正式版本时需要先统一更新版本号，再发布尚未存在的版本。

发布范围包括：

- `org.kvue:kvue-runtime` 及其 Kotlin/JS 目标包。
- `org.kvue:kvui` 及其 Kotlin/JS 目标包。
- `org.kvue:kvue-codegen-core`。
- `org.kvue:kvue-ksp`。
- `org.kvue:kvue-compiler-plugin`。
- `org.kvue:kvue-gradle-plugin` 以及插件 ID `org.kvue` 对应的 Gradle 插件标记。

工作流通过 `GITHUB_REPOSITORY` 确定发布地址，例如 `https://maven.pkg.github.com/icecream-qaq/kvue`。派生仓库运行时会使用派生仓库自身的地址。

只有发布作业申请 `packages: write`，凭据使用 GitHub 自动提供的 `GITHUB_TOKEN`，无需另建发布密钥。普通构建不会加载发布脚本，也不需要 GitHub 凭据。

如果仓库或组织策略限制令牌写入包，需要在 GitHub 侧允许该仓库工作流发布包。认证方式与快照支持见 [GitHub 官方 Gradle 包仓库说明](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry)。

## 在其他工程使用已发布的包

GitHub 的 Maven 仓库读取包也需要认证。本地开发可设置 `GITHUB_ACTOR` 为 GitHub 用户名，`GITHUB_TOKEN` 为有 `read:packages` 权限的传统类型个人访问令牌（classic）；不要将令牌写入工程文件。其他仓库的工作流使用 `GITHUB_TOKEN` 时，需要有相应的包读取权限。

在消费工程的 `settings.gradle.kts` 中，同时为插件解析和普通依赖解析添加仓库：

```kotlin
pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/icecream-qaq/kvue")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
            content { includeGroupByRegex("org\\.kvue(\\..*)?") }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/icecream-qaq/kvue")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
            content { includeGroupByRegex("org\\.kvue(\\..*)?") }
        }
    }
}
```

然后在 `build.gradle.kts` 中使用 `id("org.kvue") version "0.1.0-SNAPSHOT"` 和 `implementation("org.kvue:kvui:0.1.0-SNAPSHOT")`；完整插件接入配置见 [IDE 与构建集成](ide-and-build.md#gradle-插件做了什么)。若设置了其他仓库管理策略，请将上面的 Maven 仓库合并到实际生效的配置中。

## 本地验证发布任务

无需上传包即可检查远端发布任务是否覆盖全部模块：

```powershell
./gradlew.bat --init-script tools/publish-github.gradle '-Pkvue.exposeGeneratedSources=true' `
    publishAllPublicationsToGitHubPackagesRepository `
    :kvue-gradle-plugin:publishAllPublicationsToGitHubPackagesRepository `
    --dry-run
```

工作区内的实际 Maven 打包验证可以使用：

```powershell
./gradlew.bat --init-script tools/publish-demo.gradle '-Pkvue.exposeGeneratedSources=true' `
    publishAllPublicationsToKvueDemoRepository `
    :kvue-gradle-plugin:publishAllPublicationsToKvueDemoRepository
```

该命令将发布产物写入 `.gradle/kvue-demo-repository`，与演示工程使用同一套本地仓库。
