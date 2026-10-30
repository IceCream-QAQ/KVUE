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
        for (property in listOf("kvue.test.js.stdlib", "kvue.test.js.dom", "kvue.test.runtime.klib", "kvue.test.kvui.klib")) {
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

    fun testInlineSlotsAndHtmlNamesResolveFromRealKlibs() {
        myFixture.addFileToProject("demo/Panel.kt", """
            package demo
            import kvue.Component
            import kvue.annotation.*
            import kvue.vdom.RenderFun
            @VueComponent
            @SlotNames("header", "footer")
            class Panel : Component() {
                @Emit private val close: () -> Unit by event()
                override val body = RenderFun { slot("header"); slot(); slot("footer") }
            }
        """.trimIndent())
        myFixture.configureByText("Slots.kt", """
            package demo
            import kvue.vdom.RenderFun
            import kvui.header
            import kvui.text
            val sam = kvue.vdom.ComponentContent<PanelVue.Content> {
                slot("header") { text("sam") }
                slots.footer { text("sam footer") }
            }
            val content = RenderFun {
                Panel {
                    slot("header") { text("title") }
                    header { text("actual HTML header") }
                    slots.footer { text("footer") }
                }.slot("extra") { text("extra") }.onClose {}
            }
        """.trimIndent())
        publishNativeComponents()
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
    }

    fun testInlineSlotsReportMisplacementAndSharedDefaultLocals() {
        myFixture.addFileToProject("demo/Panel.kt", """
            package demo
            import kvue.Component
            import kvue.annotation.*
            import kvue.vdom.RenderFun
            @VueComponent @SlotNames("header")
            class Panel : Component() { override val body = RenderFun {} }
        """.trimIndent())
        myFixture.configureByText("BadSlots.kt", """
            package demo
            import kvue.vdom.RenderFun
            import kvui.text
            val content = RenderFun {
                Panel {
                    val shared = "default local"
                    slots.header { text(shared) }
                }
                Panel { if (true) slot("header") { text("nested declaration") } }
            }
        """.trimIndent())
        publishNativeComponents()
        val descriptions = myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description.orEmpty() }
        assertTrue(descriptions.toString(), descriptions.any { it.contains("Move shared declarations") })
        assertTrue(descriptions.toString(), descriptions.any { it.contains("Declare slots directly") })
    }

    fun testScopedReceiverTypesCompletionAndErrorsFromRealKlibs() {
        myFixture.addFileToProject("demo/Rows.kt", """
            package demo
            import kvue.Component
            import kvue.annotation.*
            import kvue.vdom.RenderFun
            data class Row(val name: String)
            interface Fields<T> { val row: T }
            @VueComponent class Rows : Component() {
                @Slot("item") interface Item : Fields<Row> {
                    val index: Int
                    val note: String?
                    @SlotProp("slotProps") val collision: String
                }
                override val body = RenderFun {}
            }
        """.trimIndent())
        myFixture.configureByText("ScopedUsage.kt", """
            package demo
            import kvue.vdom.RenderFun
            import kvui.*
            val content = RenderFun {
                Rows {
                    slots.item {
                        val label: String = this.row.name
                        val position: Int = index
                        val optional: String? = note
                        text(collision)
                        div { text(label + position + optional) }
                    }
                    slot("unknown") { text(slotProps.extra as String) }
                }
            }
        """.trimIndent())
        publishNativeComponents()
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
        myFixture.configureByText("ScopedCompletion.kt", """
            package demo
            import kvue.vdom.RenderFun
            val completionContent = RenderFun { Rows { slots.item { this.<caret> } } }
        """.trimIndent())
        val names = myFixture.completeBasic().map { it.lookupString }
        assertTrue(names.toString(), names.containsAll(listOf("row", "index", "note", "slotProps")))
        myFixture.configureByText("ScopedErrors.kt", """
            package demo
            import kvue.vdom.RenderFun
            val invalidContent = RenderFun { Rows { slots.item { val wrong: String = index } } }
        """.trimIndent())
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description.orEmpty() }
        assertEquals(errors.toString(), 1, errors.size)
        assertTrue(errors.toString(), errors.single().contains("type mismatch", ignoreCase = true))
    }

    fun testImportedScopedReceiversUseTypesFromTheImporterReport() {
        val caller = myFixture.configureByText("ImportedSlot.kt", """
            import widgets.Rows
            import kvue.vdom.RenderFun
            import kvui.text
            val content = RenderFun { Rows { slots.default {
                val number: Double = index
                val selectedOrMissing: Boolean? = selected
                text((row.name as String) + number + selectedOrMissing)
            } } }
        """.trimIndent()) as KtFile
        val report = JsonParser.parseString("""
            {"schemaVersion":2,"components":[{"package":"widgets","name":"Rows","module":"widgets","exportName":"Rows",
              "props":[],"events":[],"slots":["default"],"scopedSlots":[{"name":"default","properties":[
                {"name":"row","memberName":"row","kotlin":"dynamic"},{"name":"index","memberName":"index","kotlin":"Double"},
                {"name":"selected","memberName":"selected","kotlin":"Boolean?"}]}]}],
              "schemas":[{"package":"widgets","name":"Rows","text":"package widgets\ninterface Rows"}]}
        """.trimIndent()).asJsonObject
        val root = Path.of(caller.virtualFile.parent.path)
        val imports = ExternalSnapshot(listOf(ExternalLibrary(root.resolve("kvue.components.json"), listOf(root), ExternalComponents.parse(report))))
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val service = project.service<KvueProjectService>()
        val scan = allowAnalysisOnEdt { ReadAction.compute<KvueProjectService.Scan, RuntimeException> { service.scan(imports) } }
        assertEmpty(scan.diagnostics)
        WriteAction.run<RuntimeException> { service.publish(scan) }
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
    }

    fun testSlotDeclarationsNestedInElementsAreNotComponentOutlets() {
        myFixture.addFileToProject("demo/Panel.kt", """
            package demo
            import kvue.Component
            import kvue.annotation.*
            import kvue.vdom.RenderFun
            @VueComponent @SlotNames("header")
            class Panel : Component() { override val body = RenderFun {} }
        """.trimIndent())
        myFixture.configureByText("Host.kt", """
            package demo
            import kvue.Component
            import kvue.vdom.RenderFun
            import kvui.div
            import kvui.text
            class Host : Component() {
                override val body = RenderFun {
                    Panel {
                        slot("header") { text("declared") }
                        slot("footer")
                        div { slot("header") { text("misplaced") } }
                    }
                    div { slot("item", mapOf("row" to 1)) }
                }
            }
        """.trimIndent())
        publishNativeComponents()
        // Inside a component, the nested block must not silently become an outlet with lambda props.
        val descriptions = myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description.orEmpty() }
        assertEquals(descriptions.toString(), 1, descriptions.size)
        assertTrue(descriptions.single(), descriptions.single().contains("declares a slot for a child component"))
    }

    fun testOptionalPropsWithoutDefaultsAreReported() {
        myFixture.configureByText("Props.kt", """
            package demo
            import kvue.Component
            import kvue.annotation.Prop
            import kvue.vdom.RenderFun
            class Card : Component() {
                @Prop private val title by property<String>()
                @Prop(name = "note-text") private val note by property<String?>()
                @Prop(required = true) private val id by property<String>()
                @Prop private val count by property(0)
                @Prop private val subtitle by property<String?>(null)
                private val plain by property<String>()
                override val body = RenderFun {}
            }
        """.trimIndent())
        val descriptions = myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description.orEmpty() }
        assertEquals(descriptions.toString(), 2, descriptions.size)
        assertTrue(descriptions.toString(), descriptions.any { it.contains("Optional prop 'title' has no default value") })
        assertTrue(descriptions.toString(), descriptions.any { it.contains("Optional prop 'note' has no default value") })
    }

    fun testSlotNamespaceCompletionDoesNotOfferHtmlElements() {
        myFixture.addFileToProject("demo/Panel.kt", """
            package demo
            import kvue.Component
            import kvue.annotation.*
            import kvue.vdom.RenderFun
            @VueComponent @SlotNames("header", "footer")
            class Panel : Component() { override val body = RenderFun {} }
        """.trimIndent())
        myFixture.configureByText("SlotCompletion.kt", """
            package demo
            import kvue.vdom.RenderFun
            import kvui.*
            val content = RenderFun { Panel { slots.<caret> } }
        """.trimIndent())
        publishNativeComponents()
        val names = myFixture.completeBasic().map { it.lookupString }
        assertTrue(names.toString(), "header" in names)
        assertTrue(names.toString(), "footer" in names)
        assertFalse(names.toString(), "div" in names)
    }

    private fun publishNativeComponents() {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val service = project.service<KvueProjectService>()
        val scan = allowAnalysisOnEdt { ReadAction.compute<KvueProjectService.Scan, RuntimeException> { service.scan(ExternalSnapshot()) } }
        assertEmpty(scan.diagnostics)
        WriteAction.run<RuntimeException> { service.publish(scan) }
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
