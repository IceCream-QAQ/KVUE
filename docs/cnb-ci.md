# CNB 构建与发布

[.cnb.yml](../.cnb.yml) 提供与 GitHub Actions 对应的检查和打包流程：六个 Maven 模块发布到 [IceCream/maven](https://cnb.cool/IceCream/maven)，IDEA 插件 ZIP 上传到当前代码仓库的 commit 附件。

## 触发与执行顺序

| 触发方式 | 检查与构建 | commit 附件 | Maven 发布 |
| --- | --- | --- | --- |
| 推送到 master | 执行 | 上传 | 发布 |
| 向 master 创建或更新拉取请求 | 执行 | 跳过 | 跳过 |
| master 分支页点击「构建并发布 KVUE」 | 执行 | 上传 | 发布 |

手动按钮定义在 [.cnb/web_trigger.yml](../.cnb/web_trigger.yml)。

流水线顺序执行主工程和 Gradle 插件检查、本地 Maven 发布验证、IDEA 插件测试与打包，全部通过后上传插件附件并发布 Maven 包。主工程和 IDEA 构建共享生成器模块的构建目录，因此在同一工作区内顺序执行。

推送和手动发布共用流水线锁，避免同时写入同一组快照版本。不会根据构建号更改 Maven 版本，当前继续使用 0.1.0-SNAPSHOT。

## 构建环境

[构建镜像](../.cnb/Dockerfile) 包含 JDK 21、JDK 25、Chrome、Xvfb 和所需字体、桌面运行库。使用 amd64 构建节点和 8 核配置；CNB 按 Dockerfile 和 Chrome 启动脚本缓存环境镜像。

主工程使用 JDK 21，IDEA 插件使用 JDK 25，Gradle 可以发现两套工具链。浏览器测试通过容器专用启动脚本运行 Chrome，IDEA 测试使用 Xvfb。Gradle Wrapper 下载指定的 Gradle，Kotlin/JS 依赖管理下载所需的 Node.js。

## Maven 制品库与认证

仓库页面：

[https://cnb.cool/IceCream/maven](https://cnb.cool/IceCream/maven)

Gradle 使用的 Maven 服务地址：

[https://maven.cnb.cool/IceCream/maven/-/packages/](https://maven.cnb.cool/IceCream/maven/-/packages/)

[发布脚本](../tools/publish-cnb.gradle) 使用用户名 cnb，密码直接读取 CNB 内置的 CNB_TOKEN。令牌由平台注入，脚本不会将它写入工程文件；附件插件也默认使用同一个内置令牌，无需另外配置发布密钥。

发布覆盖运行库、KVUI、共享生成器、KSP、编译器插件、Gradle 插件，以及 Kotlin/JS 目标包和 Gradle 插件标记，共九项 Maven 发布产物。

CNB_TOKEN 的权限受触发事件、触发者对目标制品库的权限及平台资源范围限制。PR 只执行检查；公开代码仓库的内置令牌只能访问公开资源，Fork 仓库的内置令牌只能访问当前仓库。若发布收到 403，应核对目标制品库和触发者的权限。参见 [CNB 内置令牌说明](https://docs.cnb.cool/zh/build/build-in-env.html#cnb-token)。

持续发布快照时，IceCream/maven 的制品覆盖策略需要允许 Maven Snapshot 更新。如果返回 409，请在制品库的策略管理中检查 Maven Snapshot 策略。参见 [CNB Maven 文档](https://docs.cnb.cool/zh/artifact/maven.html)。

多模块 Maven 发布不是原子操作，失败时可能已有部分包上传；流水线会报告失败，不会把发布错误当作成功。

## 下载 IDEA 插件

在触发构建的代码仓库中打开对应 commit 的详情和附件列表。文件名为：

    kvue-idea-0.1.0-SNAPSHOT-<CNB_BUILD_ID>.zip

构建流水号用于区分同一 commit 的多次重建，附件保留 30 天。下载后直接在 IDEA 插件设置中选择「从磁盘安装插件」，选中该 ZIP 即可。

附件在 Maven 发布之前上传，所以 Maven 发布失败时，已经上传的插件仍可下载。上传使用 [CNB 官方附件插件](https://cnb.cool/cnb/plugins/cnbcool/attachments)，明确以 CNB_COMMIT 为目标。

## 本地检查发布配置

使用 JDK 21，可以不提供令牌、不上传任何包，验证全部远端发布任务：

    ./gradlew.bat --init-script tools/publish-cnb.gradle '-Pkvue.exposeGeneratedSources=true' publishAllPublicationsToCnbMavenRepository :kvue-gradle-plugin:publishAllPublicationsToCnbMavenRepository --dry-run

移除 --dry-run 会实际上传，需要有效且有相应权限的 CNB_TOKEN。普通本地开发继续使用原有的本地 Maven 发布流程。
