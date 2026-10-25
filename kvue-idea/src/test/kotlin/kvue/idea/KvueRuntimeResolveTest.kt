@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt::class)

package kvue.idea

import com.google.gson.JsonParser
import com.intellij.facet.FacetManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.impl.libraries.LibraryEx
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.TestApplicationManager
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.idea.facet.KotlinFacetType
import org.jetbrains.kotlin.idea.facet.KotlinFacet
import org.jetbrains.kotlin.idea.base.platforms.KotlinJavaScriptLibraryKind
import org.jetbrains.kotlin.platform.js.JsPlatforms
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.nio.file.Path

/** No runtime source stubs: these are the same Kotlin/JS binaries the demo consumes. */
class KvueRuntimeResolveTest : LightJavaCodeInsightFixtureTestCase() {
    private var kotlinFacet: KotlinFacet? = null
    override fun getProjectDescriptor() = JAVA_21
    override fun getTempDirFixture() = TempDirTestFixtureImpl()

    override fun setUp() {
        TestApplicationManager.getInstance()
        ExtensionPointName.create<Any>("com.intellij.postStartupActivity").point.unregisterExtensions(
            { name, adapter -> !(name == "Z.Z.Z.Z.Z" && adapter.pluginDescriptor.pluginId.idString == "com.intellij.modules.ultimate") }, false,
        )
        super.setUp()
        val root = myFixture.tempDirFixture.findOrCreateDir("")
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(module, root)
        WriteAction.run<RuntimeException> {
            val manager = FacetManager.getInstance(module)
            val type = KotlinFacetType.INSTANCE
            val configuration = type.createDefaultConfiguration()
            configuration.settings.useProjectSettings = false
            configuration.settings.compilerArguments = K2JSCompilerArguments()
            configuration.settings.targetPlatform = JsPlatforms.defaultJsPlatform
            manager.createModifiableModel().apply {
                kotlinFacet = manager.createFacet(type, "Kotlin", configuration, null)
                addFacet(kotlinFacet!!)
                commit()
            }
        }
        for (property in listOf("kvue.test.js.stdlib", "kvue.test.js.dom", "kvue.test.runtime.klib")) {
            val library = File(System.getProperty(property))
            assertTrue("Missing test library: $library", library.isFile)
            VfsRootAccess.allowRootAccess(testRootDisposable, library.parent)
            PsiTestUtil.addLibrary(module, property, library.parent, library.name)
            val dependency = ModuleRootManager.getInstance(module).orderEntries.filterIsInstance<LibraryOrderEntry>()
                .single { it.libraryName == property }.library!!
            WriteAction.run<RuntimeException> {
                (dependency.modifiableModel as LibraryEx.ModifiableModelEx).apply {
                    kind = KotlinJavaScriptLibraryKind
                    getUrls(OrderRootType.CLASSES).forEach { removeRoot(it, OrderRootType.CLASSES) }
                    addRoot("jar://" + library.absolutePath.replace('\\', '/') + "!/", OrderRootType.CLASSES)
                    commit()
                }
            }
        }
    }

    override fun tearDown() {
        try {
            kotlinFacet?.let { facet ->
                WriteAction.run<RuntimeException> {
                    FacetManager.getInstance(module).createModifiableModel().apply { removeFacet(facet); commit() }
                }
            }
        } finally { super.tearDown() }
    }

    fun testRefValueAndStyleReceiversResolveFromRealRuntimeKlib() {
        myFixture.configureByText("RuntimeUsage.kt", """
            import kvue.ComponentRef
            import kvue.vdom.knode.ElementNode
            import kvue.vdom.knode.css
            import kvue.vdom.knode.style
            val reference = ComponentRef<String> { it.toString() }
            val value: String? = reference.value
            val delegated by reference
            fun node() = ElementNode("button")
                .css { +"counter-increment" }
                .style { this["fontWeight"] = "600" }
        """.trimIndent())
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
    }

    fun testNativeAndImportedChainsUseRealRuntimeTypesBeforeKsp() {
        myFixture.addFileToProject("demo/Greeting.kt", """
            package demo
            import kvue.Component
            import kvue.annotation.*
            import kvue.vdom.RenderFun
            @VueComponent class Greeting : Component() {
                @Expose fun greet(value: String) {}
                @Emit private val reset: () -> Unit by event()
                override val body = RenderFun {}
            }
        """.trimIndent())
        val caller = myFixture.configureByText("Playground.kt", """
            package demo
            import widgets.Input
            import widgets.InputVue
            import kvue.Component
            import kvue.vdom.RenderFun
            import kvue.vdom.knode.css
            import kvue.vdom.knode.style
            class Playground : Component() {
                private val input = InputVue.ref()
                private val greeting = GreetingVue.ref()
                override val body = RenderFun {
                    Input().ref(input)
                        .css { +"counter-increment" }
                        .style { this["fontWeight"] = "600" }
                        .onClick { input.value?.focus(); greeting.value?.greet("KVUE") }
                    Greeting().ref(greeting).onReset {}
                }
            }
        """.trimIndent()) as KtFile
        val report = JsonParser.parseString("""
            {"schemaVersion":2,"components":[{"package":"widgets","name":"Input","module":"widgets","exportName":"Input",
              "props":[],"events":[{"name":"click","memberName":"emitClick","arguments":[],"variadic":false}],
              "methods":[{"name":"focus","memberName":"focus","parameters":[],"returns":"Unit","variadic":false}]}],
              "schemas":[{"package":"widgets","name":"Input","text":"package widgets\ninterface Input"}]}
        """.trimIndent()).asJsonObject
        val root = Path.of(caller.virtualFile.parent.path)
        val imports = ExternalSnapshot(listOf(ExternalLibrary(root.resolve("kvue.components.json"), listOf(root), ExternalComponents.parse(report))))
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val service = project.service<KvueProjectService>()
        val scan = allowAnalysisOnEdt { ReadAction.compute<KvueProjectService.Scan, RuntimeException> { service.scan(imports) } }
        assertEmpty(scan.diagnostics)
        WriteAction.run<RuntimeException> { service.publish(scan) }
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
        assertNull(myFixture.tempDirFixture.getFile("build/generated"))
    }
}
