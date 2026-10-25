# 在 IDEA 中快速创建 KVUE 工程

安装 KVUE IDEA 插件后，在欢迎页选择「新建项目」，或通过「文件 → 新建 → 项目」打开向导，在左侧选择 **KVUE**。该入口需要启用 IDEA 自带的 Gradle 插件。

## 填写项目选项

| 选项 | 说明 |
| --- | --- |
| 名称和位置 | 使用 IDEA 的标准项目名称、目录选择和 Git 初始化选项 |
| 包名 | 示例 Kotlin 文件所在的包，默认为 `app` |
| 模板 | 基础 KVUI 界面，或带有 Element Plus 的界面 |
| JDK | 选择 JDK 21 或更高版本，也可使用 IDEA 的 SDK 选择器添加 JDK |
| KVUE 版本 | 默认为 `0.1.0-SNAPSHOT`；运行库、KVUI、KSP 和编译器插件使用同一版本 |
| 依赖仓库 | 默认 GitHub Packages，也可选择已发布开发依赖的本地 Maven 仓库目录 |

两个模板都包含可编辑的名字、计数按钮和重置按钮。基础模板使用原生 HTML 封装；Element Plus 模板使用 `ElInput`、`ElButton` 和 `ElCard`，同时配置 npm 依赖、样式导入及组件声明分析。

向导使用已随插件打包的模板和 Gradle Wrapper 创建文件。生成阶段无需下载模板，也不会覆盖已有同名文件。首次 Gradle 同步和构建需要获取依赖。

## 选择依赖仓库

### GitHub Packages

同步前，在用户目录下的 `~/.gradle/gradle.properties` 配置：

```properties
kvue.github.user=你的GitHub用户名
kvue.github.token=有read:packages权限的传统类型个人访问令牌
```

也可以在启动 IDEA 的环境中设置 `GITHUB_ACTOR` 和 `GITHUB_TOKEN`。显式配置的 Gradle 属性优先。

令牌应配置在用户目录或环境变量中，不要写入工程的 `gradle.properties`。向导不会保存或生成令牌。仓库中需要已经存在所选择版本的 KVUE 包，发布与认证说明见 [持续集成与包发布](ci-and-publishing.md)。

### 本地 Maven 仓库

开发 KVUE 本身时，可以先按照 [README 的本地发布步骤](../README.md#运行交互示例) 发布各模块，再在向导中选择本仓库的 `.gradle/kvue-demo-repository` 目录。

本地模式直接生成文件仓库地址，不要求 GitHub 凭据。Maven Central、Gradle 插件仓库和 npm 中的其他依赖仍需下载。

## 创建后运行

点击「创建」后，插件会：

1. 生成 Kotlin/JS 工程、Gradle Wrapper、示例组件、HTML 和样式。
2. 设置选中的 JDK，并通过 IDEA 的 Gradle 集成关联、同步项目。
3. 打开 `App.kt`，添加并选中 **KVUE 开发服务器** 运行配置，同时提供 **KVUE 生产打包** 配置。

同步完成后，运行 **KVUE 开发服务器**，在浏览器访问 [本地页面](http://127.0.0.1:5173)。开发服务器使用持续构建模式，修改 Kotlin 文件后会重新编译。

也可以使用终端：

```powershell
./gradlew.bat jsBrowserDevelopmentRun --continuous
```

生产打包使用 **KVUE 生产打包** 配置或：

```powershell
./gradlew.bat jsBrowserDistribution
```

产物位于 `build/dist/js/productionExecutable`。macOS 和 Linux 使用对应的 `./gradlew`。

IDEA 中的 Element Plus 声明分析需要可通过 `node` 命令运行的 Node.js，以及已经安装的 npm 依赖。可先运行一次上述构建；终端构建本身所需的 Node.js 由 Gradle 管理。

新工程沿用生成源的自动可见性策略：通过 KVUE IDEA 插件构建时隐藏 KVUE 生成目录，编辑器提供虚拟声明；没有插件时，先构建即可得到普通 Kotlin 源文件支持。向导不会强制改写该设置。
