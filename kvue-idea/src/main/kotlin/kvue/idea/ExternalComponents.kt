@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class, org.jetbrains.kotlin.analysis.api.KaSpiExtensionPoint::class)

package kvue.idea

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.squareup.kotlinpoet.ClassName
import kvue.codegen.*
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionFile
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionNavigationTargetsProvider
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtElement
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal data class ImportedComponent(
    val model: ComponentModel,
    val schema: String,
    val source: String?,
    val navigation: ImportedSourceLocation? = null,
    val moduleSource: String? = null,
)
internal data class ExternalLibrary(val configuration: Path, val sourceRoots: List<Path>, val components: List<ImportedComponent>)
internal data class ExternalSnapshot(val libraries: List<ExternalLibrary> = emptyList(), val watchedPaths: Set<String> = emptySet(), val diagnostics: List<String> = emptyList())

/** Runs only the bundled read-only analyzer; it never invokes Gradle, KSP, or writes project sources. */
internal object ExternalComponents {
    fun analyze(project: Project): ExternalSnapshot {
        val root = project.basePath?.let(Path::of) ?: return ExternalSnapshot()
        val settingsFile = root.resolve("kvue.ide.json")
        val settings = if (Files.isRegularFile(settingsFile)) JsonParser.parseString(Files.readString(settingsFile)).asJsonObject else JsonObject()
        val imports = settings.getAsJsonArray("imports")?.map { item ->
            val entry = item.asJsonObject
            root.resolve(entry.get("configuration").asString).normalize() to
                entry.getAsJsonArray("sourceRoots").map { root.resolve(it.asString).normalize() }
        } ?: listOf(root.resolve("kvue.components.json") to listOf(root.resolve("src/main/kotlin"), root.resolve("src/jsMain/kotlin")))
        val configured = imports.filter { Files.isRegularFile(it.first) }
        val watched = mutableSetOf(settingsFile.toString().replace('\\', '/'))
        imports.forEach { watched += it.first.toString().replace('\\', '/') }
        if (configured.isEmpty()) return ExternalSnapshot(watchedPaths = watched)
        if (!TrustedProjects.isProjectTrusted(project)) return ExternalSnapshot(watchedPaths = watched,
            diagnostics = listOf("External KVUE analysis is available after this project is trusted in the IDE."))
        val modules = settings.get("nodeModules")?.asString?.let { root.resolve(it).normalize() }
            ?: listOf(root.resolve("build/js/node_modules"), root.resolve("node_modules")).firstOrNull { Files.isDirectory(it.resolve("typescript")) }
            ?: return ExternalSnapshot(watchedPaths = watched, diagnostics = listOf("External KVUE components need locally installed TypeScript/npm dependencies. Run the normal dependency installation once."))
        val node = settings.get("nodeExecutable")?.asString ?: "node"
        val script = extractImporter()
        val libraries = mutableListOf<ExternalLibrary>()
        val diagnostics = mutableListOf<String>()
        for ((configuration, sourceRoots) in configured) {
            val command = GeneralCommandLine(node, script.toString(), "--analyze", "--config", configuration.toString(), "--node-modules", modules.toString())
                .withWorkDirectory(root.toFile()).withCharset(Charsets.UTF_8)
            val handler = CapturingProcessHandler(command)
            val result = try { handler.runProcess(45_000) } finally { if (!handler.isProcessTerminated) handler.destroyProcess() }
            if (result.isTimeout || result.exitCode != 0) {
                diagnostics += "${configuration.fileName}: ${if (result.isTimeout) "analysis timed out" else result.stderr.takeLast(2000)}"
                continue
            }
            val report = JsonParser.parseString(result.stdout).asJsonObject
            for (key in listOf("declarations", "sources")) report.getAsJsonArray(key)?.forEach { watched += it.asString.replace('\\', '/') }
            val components = parse(report)
            val navigationFiles = components.flatMap { listOfNotNull(it.navigation?.file, it.source, it.moduleSource) }.toSet()
            // This is the background IO phase, outside Kotlin analysis/read callbacks.
            // Refresh explicit targets even when npm/build directories are excluded from indexing.
            navigationFiles.forEach { LocalFileSystem.getInstance().refreshAndFindFileByPath(it.replace('\\', '/')) }
            watched += navigationFiles.map { it.replace('\\', '/') }
            libraries += ExternalLibrary(configuration, sourceRoots, components)
        }
        return ExternalSnapshot(libraries, watched, diagnostics)
    }

    internal fun parse(report: JsonObject): List<ImportedComponent> {
        require(report.get("schemaVersion").asInt == 2) { "Unsupported KVUE importer schema version." }
        val schemas = report.getAsJsonArray("schemas").associate { value ->
            val schema = value.asJsonObject
            (schema.get("package").asString + "." + schema.get("name").asString) to schema.get("text").asString
        }
        return report.getAsJsonArray("components").map { value ->
            val component = value.asJsonObject
            val className = ClassName(component.get("package").asString, component.get("name").asString)
            val props = component.getAsJsonArray("props").map { raw ->
                val prop = raw.asJsonObject
                val type = importedType(prop.get("kotlin").asString)
                PropModel(prop.get("memberName").asString, prop.get("name").asString, type, type.isNullable,
                    prop.get("required").asBoolean, prop.get("variadic").asBoolean)
            }.sortedBy { !it.required }
            val events = component.getAsJsonArray("events").map { raw ->
                val event = raw.asJsonObject
                EventModel(event.get("memberName").asString, event.get("name").asString,
                    event.getAsJsonArray("arguments").map { importedType(it.asString) }, event.get("variadic").asBoolean)
            }
            val navigation = component.get("navigation")?.takeIf { it.isJsonObject }?.asJsonObject?.let { target ->
                ImportedSourceLocation(target.get("file").asString, target.get("line").asInt, target.get("column").asInt,
                    target.get("length").asInt, target.get("text")?.takeUnless { it.isJsonNull }?.asString)
            }
            val methods = component.getAsJsonArray("methods")?.map { value ->
                val method = value.asJsonObject
                ExposedMethodModel(method.get("memberName").asString, method.get("name").asString,
                    method.getAsJsonArray("parameters").map { parameter ->
                        val argument = parameter.asJsonObject
                        ExposedParameterModel(argument.get("name").asString, importedType(argument.get("kotlin").asString),
                            argument.get("optional").asBoolean)
                    }, importedType(method.get("returns").asString), method.get("variadic").asBoolean)
            }.orEmpty()
            val exposedProperties = component.getAsJsonArray("exposedProperties")?.map { value ->
                val property = value.asJsonObject
                ExposedPropertyModel(property.get("memberName").asString, property.get("name").asString,
                    importedType(property.get("kotlin").asString), property.get("mutable").asBoolean)
            }.orEmpty()
            ImportedComponent(ComponentModel(className, props, events,
                JsImportModel(component.get("module").asString, component.get("exportName").asString),
                methods = methods, exposedProperties = exposedProperties,
                hasInstance = component.get("kind")?.asString != "functional"),
                schemas.getValue(className.canonicalName), component.get("source")?.takeUnless { it.isJsonNull }?.asString,
                navigation, component.get("moduleSource")?.takeUnless { it.isJsonNull }?.asString)
        }
    }

    private fun extractImporter(): Path {
        val resources = listOf("generate.cjs", "source-analysis.cjs").associateWith { name ->
            checkNotNull(javaClass.getResourceAsStream("/js-importer/$name")).use { it.readBytes() }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        resources.values.forEach(digest::update)
        val hash = digest.digest().joinToString("") { "%02x".format(it) }.take(16)
        val directory = PathManager.getSystemDir().resolve("kvue/importer/$hash")
        Files.createDirectories(directory)
        resources.forEach { (name, bytes) -> if (!Files.exists(directory.resolve(name))) Files.write(directory.resolve(name), bytes) }
        return directory.resolve("generate.cjs")
    }
}

internal fun ImportedComponent.virtualFiles(project: Project, configuration: Path): List<KaResolveExtensionFile> {
    val navigation = ExternalNavigation(project, this, configuration)
    val dsl = ComponentCodegen.generate(model, declarationsOnly = true).single().toString()
    fun file(fileName: String, text: String, classifiers: Set<Name>, callables: Set<Name>) = object : KaResolveExtensionFile() {
        override fun getFileName() = fileName
        override fun getFilePackageName() = FqName(model.className.packageName)
        override fun getTopLevelClassifierNames() = classifiers
        override fun getTopLevelCallableNames() = callables
        override fun buildFileText() = text
        override fun createNavigationTargetsProvider() = object : KaResolveExtensionNavigationTargetsProvider() {
            override fun KaSession.getNavigationTargets(element: KtElement): Collection<PsiElement> = navigation.targets()
        }
    }
    return listOf(
        file(model.className.simpleName + "JsSchema.kt", schema, setOf(Name.identifier(model.className.simpleName)), emptySet()),
        file(model.fileName + ".kt", dsl, model.generatedClassNames.map { Name.identifier(it.simpleName) }.toSet(), setOf(Name.identifier(model.className.simpleName))),
    )
}
