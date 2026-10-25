package kvue.idea

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.attribute.PosixFilePermission

enum class KvueStarter(private val label: String) {
    KVUI("基础界面（KVUI）"),
    ELEMENT_PLUS("Element Plus");

    override fun toString() = label
}

enum class KvueRepository(private val label: String) {
    GITHUB("GitHub Packages"),
    LOCAL("本地 Maven 仓库");

    override fun toString() = label
}

data class KvueProjectOptions(
    val name: String,
    val packageName: String = "app",
    val version: String = "0.1.0-SNAPSHOT",
    val starter: KvueStarter = KvueStarter.KVUI,
    val repository: KvueRepository = KvueRepository.GITHUB,
    val localRepository: String = "",
)

/** No IDE or network access: generation can also be exercised against a real Gradle consumer. */
object KvueProjectTemplate {
    private const val PREFIX = "/project-template/"
    private val keywords = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
        "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
        "typeof", "val", "var", "when", "while",
    )

    fun packageError(value: String): String? =
        if (value.split('.').all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*")) && it !in keywords }) null
        else "包名应由点分隔的 Kotlin 标识符组成，不能使用关键字。"

    fun versionError(value: String): String? =
        if (value.matches(Regex("[0-9][A-Za-z0-9._+\\-]*"))) null else "请输入有效的 KVUE 版本，例如 0.1.0-SNAPSHOT。"

    fun repositoryError(value: String): String? = try {
        if (value.isNotBlank() && Files.isDirectory(Path.of(value))) null else "请选择已有的本地 Maven 仓库目录。"
    } catch (_: IllegalArgumentException) {
        "本地仓库路径无效。"
    }

    fun generate(directory: Path, options: KvueProjectOptions): Path {
        require(options.name.isNotBlank() && options.name.none { it.isISOControl() || it == '/' || it == '\\' }) {
            "项目名称不能为空或包含路径分隔符、控制字符。"
        }
        require(packageError(options.packageName) == null) { packageError(options.packageName)!! }
        require(versionError(options.version) == null) { versionError(options.version)!! }
        if (options.repository == KvueRepository.LOCAL) {
            require(repositoryError(options.localRepository) == null) { repositoryError(options.localRepository)!! }
        }
        val root = directory.toAbsolutePath().normalize()
        val packagePath = options.packageName.replace('.', '/')
        val replacements = mapOf(
            "{{NAME}}" to kotlinString(options.name),
            "{{TITLE}}" to html(options.name),
            "{{PACKAGE}}" to options.packageName,
            "{{VERSION}}" to options.version,
            "{{REPOSITORY}}" to repositoryBlock(options).prependIndent("        "),
            "{{ELEMENT_PLUS_DEPENDENCY}}" to if (options.starter == KvueStarter.ELEMENT_PLUS)
                """            implementation(npm("element-plus", "2.14.7"))""" else "",
            "{{ELEMENT_PLUS_STYLE}}" to if (options.starter == KvueStarter.ELEMENT_PLUS)
                """    js("require('element-plus/dist/index.css')")""" else "",
            "{{REPOSITORY_HELP}}" to if (options.repository == KvueRepository.GITHUB)
                "依赖来自 GitHub Packages。同步前请设置 GITHUB_ACTOR 和有 read:packages 权限的 GITHUB_TOKEN（传统类型个人访问令牌），或在用户目录的 .gradle/gradle.properties 中配置 kvue.github.user 与 kvue.github.token。"
                else "依赖来自创建向导选择的本地 Maven 仓库。请先将相同版本的 KVUE 各模块发布到该仓库。",
        )
        fun text(resource: String): ByteArray {
            val source = bytes(resource).toString(Charsets.UTF_8).replace("\r\n", "\n")
            val content = Regex("\\{\\{[A-Z_]+}}").replace(source) { replacements.getValue(it.value) }
            return content.toByteArray(Charsets.UTF_8)
        }
        val files = linkedMapOf(
            "settings.gradle.kts" to text("settings.gradle.kts.template"),
            "build.gradle.kts" to text("build.gradle.kts.template"),
            "gradle.properties" to text("gradle.properties"),
            ".gitignore" to text("gitignore"),
            "README.md" to text("README.md.template"),
            "src/jsMain/kotlin/$packagePath/Main.kt" to text("Main.kt.template"),
            "src/jsMain/kotlin/$packagePath/App.kt" to text(
                if (options.starter == KvueStarter.ELEMENT_PLUS) "App.element-plus.kt.template" else "App.kt.template",
            ),
            "src/jsMain/resources/index.html" to text("index.html.template"),
            "src/jsMain/resources/app.css" to text("app.css"),
            "webpack.config.d/dev-server.js" to text("dev-server.js"),
            "gradlew" to text("gradlew"),
            "gradlew.bat" to text("gradlew.bat"),
            "gradle/wrapper/gradle-wrapper.properties" to text("gradle/wrapper/gradle-wrapper.properties"),
            "gradle/wrapper/gradle-wrapper.jar" to bytes("gradle/wrapper/gradle-wrapper.jar"),
        )
        if (options.starter == KvueStarter.ELEMENT_PLUS) {
            files["kvue.components.json"] = text("kvue.components.json.template")
        }
        // Validate the entire plan before writing anything, including existing linked directories.
        files.keys.forEach { relative ->
            val target = root.resolve(relative)
            require(!Files.exists(target, NOFOLLOW_LINKS)) { "目标文件已存在，未覆盖：$target" }
            var parent: Path? = target.parent
            while (parent != null && parent.startsWith(root)) {
                require(!Files.isSymbolicLink(parent)) { "目标目录不能是符号链接：$parent" }
                if (Files.exists(parent, NOFOLLOW_LINKS)) {
                    require(Files.isDirectory(parent)) { "目标路径不是目录：$parent" }
                    if (Files.exists(root)) {
                        require(parent.toRealPath().startsWith(root.toRealPath())) { "目标目录指向了工程外部：$parent" }
                    }
                }
                parent = parent.parent
            }
        }
        files.forEach { (relative, content) ->
            val target = root.resolve(relative)
            Files.createDirectories(target.parent)
            Files.write(target, content, CREATE_NEW)
        }
        if (root.fileSystem.supportedFileAttributeViews().contains("posix")) {
            val wrapper = root.resolve("gradlew")
            Files.setPosixFilePermissions(wrapper, Files.getPosixFilePermissions(wrapper) + setOf(
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE,
            ))
        }
        return root.resolve("src/jsMain/kotlin/$packagePath/App.kt")
    }

    private fun bytes(resource: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(PREFIX + resource)) { "缺少项目模板资源：$resource" }.use { it.readBytes() }

    private fun repositoryBlock(options: KvueProjectOptions): String {
        val source = if (options.repository == KvueRepository.GITHUB) """
            url = uri("https://maven.pkg.github.com/icecream-qaq/kvue")
            credentials {
                username = providers.gradleProperty("kvue.github.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("kvue.github.token")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        """.trimIndent() else "url = uri(${kotlinString(Path.of(options.localRepository).toAbsolutePath().toUri().toASCIIString())})"
        return "maven {\n" + source.prependIndent("    ") +
            "\n    content { includeGroupByRegex(\"org\\\\.kvue(\\\\..*)?\") }\n}"
    }

    private fun kotlinString(value: String): String = "\"" + value
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""

    private fun html(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
