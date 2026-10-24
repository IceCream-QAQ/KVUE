# KVUE × Element Plus

独立的 Kotlin/JS 示例，使用 Maven 形式的 KVUE / KVUI 开发版依赖，以及 npm 中的 Element Plus 2.14.7。

首次使用，在仓库根目录以 JDK 21 运行，准备工作区内的开发依赖仓库：

```powershell
.\gradlew.bat --init-script tools/publish-demo.gradle :kvue-codegen-core:publishAllPublicationsToKvueDemoRepository :kvue-ksp:publishAllPublicationsToKvueDemoRepository :kvue-runtime:publishAllPublicationsToKvueDemoRepository :kvui:publishAllPublicationsToKvueDemoRepository :kvue-compiler-plugin:publishAllPublicationsToKvueDemoRepository :kvue-gradle-plugin:publishAllPublicationsToKvueDemoRepository
```

然后在本目录运行：

```powershell
.\gradlew.bat jsBrowserDevelopmentRun --continuous
```

打开 http://127.0.0.1:5173。页面包含输入、开关、计数器、进度条，以及通过 props 和事件连接的原生 Kotlin 子组件。

生产构建同样在本目录运行 `./gradlew.bat jsBrowserDistribution`，产物位于
`build/dist/js/productionExecutable`。仓库根项目汇总各模块检查，网页运行和打包统一使用本示例。

用装有 KVUE 插件的 IDEA 262 打开本目录并完成 Gradle 同步。主要查看：

- `src/jsMain/kotlin/demo/Playground.kt`：`ElInput(vModel = name)`、`ElSwitch(bindModelValue = enabled)`、`GreetingCard(bindName = name)`，均直接绑定可写属性。
- `src/jsMain/kotlin/demo/GreetingCard.kt`：带 `@Prop` / `@Emit` 的 class 组件，`update:name` 会回写父组件的 name。修改声明后，编辑器会更新对应的 DSL 提示。
- 页面中的 `main`、`div`、`label`、标题和文本使用 `kvui.*`，无需维护项目私有 HTML helper。
- `kvue.components.json`：从 Element Plus 的 TypeScript 声明批量导入组件。

`exposeGeneratedSources` 没有显式设置：IDEA 插件在同步和执行任务时注入默认关闭，普通终端默认为打开。
首次依赖安装后如第三方提示尚未出现，可使用 **Tools → KVUE → Refresh Component Declarations**。

插件开发者也可以在仓库根目录使用 JDK 25 启动独立 IDEA 实例：

```powershell
.\gradlew.bat -p kvue-idea "-PideaHome=C:\path\to\IntelliJ IDEA" runIde "--args=F:/path/to/KVUE/examples/element-plus-demo"
```
