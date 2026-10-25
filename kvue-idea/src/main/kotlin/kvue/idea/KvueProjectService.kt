@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class, org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class,
    org.jetbrains.kotlin.analysis.api.KaSpiExtensionPoint::class, kotlinx.coroutines.FlowPreview::class)

package kvue.idea

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ProjectTopics
import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.*
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import org.jetbrains.kotlin.analysis.api.platform.analysisMessageBus
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModuleOutOfBlockModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModuleStateModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModuleStateModificationKind
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class KvueProjectService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    @Volatile private var snapshots: Map<KaModule, ModuleSnapshot> = emptyMap()
    @Volatile var diagnostics: List<String> = emptyList()
        private set
    private val requests = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val externalRequests = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile private var external = ExternalSnapshot()

    init {
        if (!ApplicationManager.getApplication().isUnitTestMode) {
            TrustedProjectsListener.onceWhenProjectTrusted(this, java.util.function.Consumer { trusted -> if (trusted == project) requestExternalRefresh() })
            project.messageBus.connect(this).subscribe(ProjectTopics.PROJECT_ROOTS, object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) { requestExternalRefresh() }
            })
            project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it.path in external.watchedPaths || it.path.endsWith("/kvue.ide.json") ||
                        it.path.endsWith("/kvue.components.json") || (external.libraries.isEmpty() && it.path.contains("/node_modules/")) }) {
                        requestExternalRefresh()
                    } else if (events.any { it.path.endsWith(".kt") }) requestRefresh()
                }
            })
            PsiManager.getInstance(project).addPsiTreeChangeListener(object : PsiTreeChangeAdapter() {
                override fun childrenChanged(event: PsiTreeChangeEvent) = changed(event)
                override fun childAdded(event: PsiTreeChangeEvent) = changed(event)
                override fun childRemoved(event: PsiTreeChangeEvent) = changed(event)
                override fun childReplaced(event: PsiTreeChangeEvent) = changed(event)
                override fun propertyChanged(event: PsiTreeChangeEvent) = changed(event)
                private fun changed(event: PsiTreeChangeEvent) {
                    if (event.file == null || event.file is KtFile) requestRefresh()
                }
            }, this)
            // External analysis has its own queue: Kotlin edits must neither wait
            // for Node nor cancel an in-flight library analysis.
            scope.launch {
                externalRequests.debounce(400).collectLatest {
                    val refreshed = withContext(Dispatchers.IO) {
                        try { ExternalComponents.analyze(project) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            ExternalSnapshot(watchedPaths = external.watchedPaths,
                                diagnostics = listOf("External component analysis failed: ${error.message}"))
                        }
                    }
                    external = refreshed
                    requestRefresh()
                }
            }
            scope.launch {
                requests.debounce(200).collectLatest {
                    try {
                        val scan = smartReadAction(project) { scan(external) }
                        withContext(Dispatchers.EDT) { writeAction { publish(scan) } }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (cancelled: com.intellij.openapi.progress.ProcessCanceledException) { requestRefresh() }
                    catch (error: Exception) {
                        diagnostics = listOf(error.message ?: error.javaClass.simpleName)
                        Logger.getInstance(KvueProjectService::class.java).warn("Unable to refresh KVUE declarations", error)
                    }
                }
            }
            requestExternalRefresh()
        }
    }

    fun requestRefresh() { if (!project.isDisposed) requests.tryEmit(Unit) }
    fun requestExternalRefresh() { if (!project.isDisposed) externalRequests.tryEmit(Unit); requestRefresh() }
    fun statusText(): String = "${snapshots.values.sumOf { snapshot -> snapshot.files.sumOf { it.getTopLevelCallableNames().size } }} component declarations available in the editor.\n" +
        if (diagnostics.isEmpty()) "No analysis errors." else diagnostics.take(20).joinToString("\n")
    internal fun snapshot(module: KaModule): ModuleSnapshot? = snapshots[module]

    internal data class Scan(val modules: Map<KaModule, ModuleSnapshot>, val diagnostics: List<String>, val revision: Long)

    internal fun scan(imports: ExternalSnapshot = external): Scan {
        val revision = PsiManager.getInstance(project).modificationTracker.modificationCount
        val psiManager = PsiManager.getInstance(project)
        val files = FileTypeIndex.getFiles(KotlinFileType.INSTANCE, GlobalSearchScope.projectScope(project))
            .mapNotNull { psiManager.findFile(it) as? KtFile }
        val components = linkedMapOf<KaModule, MutableList<EditorComponent>>()
        val moduleFiles = linkedMapOf<KaModule, MutableList<KtFile>>()
        val ownedNames = snapshots.mapValues { it.value.componentNames.toMutableSet() }.toMutableMap()
        val problems = imports.diagnostics.toMutableList()
        for (file in files) {
            if (isGeneratedImplementation(file) || isGeneratedSchema(file)) continue
            val module = KaModuleProvider.getModule(project, file, null) as? KaSourceModule ?: continue
            moduleFiles.getOrPut(module) { mutableListOf() }.add(file)
            for (declaration in file.declarations.filterIsInstance<KtClass>()) {
                if (declaration.annotationEntries.isEmpty()) continue
                try {
                    val component = KotlinComponentReader.read(declaration) ?: continue
                    components.getOrPut(module) { mutableListOf() }.add(component)
                    ownedNames.getOrPut(module) { mutableSetOf() }.add(component.model.className.canonicalName)
                } catch (cancelled: com.intellij.openapi.progress.ProcessCanceledException) { throw cancelled }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (invalid: IllegalArgumentException) {
                    declaration.fqName?.asString()?.let { ownedNames.getOrPut(module) { mutableSetOf() }.add(it) }
                    problems += "${file.name}:${declaration.name}: ${invalid.message}"
                }
                catch (invalid: IllegalStateException) {
                    declaration.fqName?.asString()?.let { ownedNames.getOrPut(module) { mutableSetOf() }.add(it) }
                    problems += "${file.name}:${declaration.name}: ${invalid.message}"
                }
            }
        }
        val externalFiles = mutableMapOf<KaModule, MutableList<org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionFile>>()
        val externalOrigins = mutableMapOf<KaModule, MutableList<String>>()
        for (library in imports.libraries) {
            val owners = moduleFiles.filterValues { anchors -> anchors.any { file ->
                library.sourceRoots.any { root -> Path.of(file.virtualFile.path).startsWith(root) }
            } }.keys
            if (owners.size != 1) {
                problems += "${library.configuration.fileName}: expected one source module for ${library.sourceRoots}; found ${owners.size}."
                continue
            }
            val owner = owners.single()
            for (component in library.components) {
                if (components[owner].orEmpty().any { it.model.className == component.model.className }) {
                    problems += "Duplicate native/imported component ${component.model.className}."
                    continue
                }
                ownedNames.getOrPut(owner) { mutableSetOf() }.add(component.model.className.canonicalName)
                externalFiles.getOrPut(owner) { mutableListOf() }.addAll(component.virtualFiles(project, library.configuration))
                externalOrigins.getOrPut(owner) { mutableListOf() }
                    .add("${component.model.className.canonicalName}|${component.navigation}|${component.source}|${component.moduleSource}|${library.configuration}")
            }
        }
        ownedNames.keys.retainAll(moduleFiles.keys)
        val fileIndex = ProjectFileIndex.getInstance(project)
        fun originalOwner(file: KtFile) = ModuleUtilCore.findModuleForFile(file.virtualFile, project) to fileIndex.isInTestSourceContent(file.virtualFile)
        val modules = ownedNames.mapValues { (module, names) ->
            val declarations = components[module].orEmpty().sortedBy { it.model.className.canonicalName }.map(::ComponentVirtualFile) + externalFiles[module].orEmpty()
            val ownerKeys = moduleFiles[module].orEmpty().map(::originalOwner).toSet()
            val shadowed = files.filter { file ->
                (isGeneratedImplementation(file) || isGeneratedSchema(file)) &&
                    // Kotlin's refined content scope excludes already-shadowed files.
                    // Use the original IDE roots to keep their ownership across refreshes.
                    originalOwner(file).let { it.first != null && it in ownerKeys } &&
                    (file.packageFqName.asString() + "." + if (isGeneratedImplementation(file)) file.name.removeSuffix("KVueGenerated.kt") else file.name.removeSuffix(".kt")) in names
            }.map { it.virtualFile.path }.toSet()
            fun origin(pointer: SmartPsiElementPointer<out PsiElement>): String = pointer.element?.let { element ->
                val file = element.containingFile?.virtualFile
                "${file?.url}:${System.identityHashCode(file)}:${element.textOffset}"
            }.orEmpty()
            val nativeOrigins = components[module].orEmpty().sortedBy { it.model.className.canonicalName }.joinToString("\n") { component ->
                origin(component.origin) + component.members.toSortedMap().entries.joinToString { (name, pointer) -> "$name:${origin(pointer)}" }
            }
            val fingerprint = declarations.joinToString("\n") { it.buildFileText() } + shadowed.sorted().joinToString("\n") + nativeOrigins +
                externalOrigins[module].orEmpty().sorted().joinToString("\n")
            ModuleSnapshot(declarations, shadowed, names.toSet(), fingerprint)
        }
        return Scan(modules, problems, revision)
    }

    /** Must run in a write action; published models never retain Analysis API symbols or types. */
    internal fun publish(scan: Scan) {
        if (PsiManager.getInstance(project).modificationTracker.modificationCount != scan.revision) { requestRefresh(); return }
        val previous = snapshots
        val changed = scan.modules.keys.filter { previous[it]?.fingerprint != scan.modules[it]?.fingerprint }
        val publisher = project.analysisMessageBus.syncPublisher(KotlinModificationEvent.TOPIC)
        // Replacing virtual files also changes their identities/content scope. An
        // out-of-block event alone can leave K2's module scope pointing at old PSI.
        changed.forEach { publisher.onModification(KotlinModuleStateModificationEvent(it, KotlinModuleStateModificationKind.UPDATE)) }
        snapshots = scan.modules
        diagnostics = scan.diagnostics
        changed.forEach { module ->
            publisher.onModification(KotlinModuleOutOfBlockModificationEvent(module))
        }
        // A navigation-only refresh changes no physical Kotlin PSI. Its reference cache can otherwise
        // keep a resolved declaration tied to the previous resolve-extension file/provider.
        if (changed.isNotEmpty()) PsiManager.getInstance(project).dropResolveCaches()
        if (changed.isNotEmpty() && !ApplicationManager.getApplication().isUnitTestMode) DaemonCodeAnalyzer.getInstance(project).restart()
    }

    private fun isGeneratedImplementation(file: KtFile): Boolean {
        val path = file.virtualFile.path
        return file.name.endsWith("KVueGenerated.kt") && (path.contains("/build/generated/ksp/") ||
            Regex("/build/generated/kvue/build-only/[^/]+/[^/]+/kvue/").containsMatchIn(path))
    }
    private fun isGeneratedSchema(file: KtFile): Boolean =
        Regex("/build/generated/kvue/js-(test-)?components/kotlin/").containsMatchIn(file.virtualFile.path)

    override fun dispose() { snapshots = emptyMap() }
}

class KvueStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) { project.service<KvueProjectService>().requestExternalRefresh() }
}
