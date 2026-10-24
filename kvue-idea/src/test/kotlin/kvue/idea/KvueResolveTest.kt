@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class, org.jetbrains.kotlin.analysis.api.KaSpiExtensionPoint::class,
    org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt::class)

package kvue.idea

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.TestApplicationManager
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.facet.FacetManager
import org.jetbrains.kotlin.idea.facet.KotlinFacetType
import org.jetbrains.kotlin.platform.js.JsPlatforms
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionProvider
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.*
import java.io.File
import java.nio.file.Path

class KvueResolveTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor() = JAVA_21

    override fun setUp() {
        TestApplicationManager.getInstance()
        // IU 262.10968.63's flat unit-test classpath resolves this obfuscated
        // product startup implementation to an interface. It is unrelated to
        // Kotlin/KVUE; isolate only that broken test-host contribution.
        // This API's predicate is a KEEP filter.
        ExtensionPointName.create<Any>("com.intellij.postStartupActivity").point.unregisterExtensions(
            { name, adapter -> !(name == "Z.Z.Z.Z.Z" && adapter.pluginDescriptor.pluginId.idString == "com.intellij.modules.ultimate") }, false,
        )
        super.setUp()
        val stdlib = File(System.getProperty("kvue.test.stdlib"))
        PsiTestUtil.addLibrary(module, "kotlin-stdlib", stdlib.parent, stdlib.name)
        myFixture.addFileToProject("kvue/Annotations.kt", """
            package kvue.annotation
            annotation class VueComponent
            annotation class JsComponent(val module: String, val exportName: String = "default")
            annotation class Prop(val required: Boolean = false, val name: String = "", val variadic: Boolean = false)
            annotation class Emit(val name: String = "", val variadic: Boolean = false)
            @Target(AnnotationTarget.VALUE_PARAMETER)
            annotation class VBind(val prop: String, val event: String, val variadic: Boolean = false)
            @Target(AnnotationTarget.VALUE_PARAMETER)
            annotation class RequiredBindingProp
        """.trimIndent())
        myFixture.addFileToProject("kvue/Runtime.kt", """
            package kvue
            import kotlin.reflect.KProperty
            open class Component {
                protected fun <T> property(value: T): Delegate<T> = Delegate()
                protected fun <T> property(): Delegate<T> = Delegate()
                protected fun <T> ref(value: T): Delegate<T> = Delegate()
                protected fun <F> event(): Delegate<F> = Delegate()
            }
            class Delegate<T> {
                operator fun getValue(owner: Any?, property: KProperty<*>): T = error("test")
                operator fun setValue(owner: Any?, property: KProperty<*>, value: T) {}
            }
            class ComponentDefinition<T>
            class JsComponentDefinition
            sealed interface OptionalProp<out T> { data object Omitted : OptionalProp<Nothing> }
            fun <T> omittedBinding(): T = error("test")
            fun <T> requiredBindingProp(name: String): T = error("test")
        """.trimIndent())
        myFixture.addFileToProject("kvue/Render.kt", """
            package kvue.vdom
            class RenderGroup
            fun interface RenderFun { fun RenderGroup.render() }
        """.trimIndent())
        myFixture.addFileToProject("kvue/Element.kt", "package kvue.vdom.knode\nclass ElementNode")
        assertTrue(KaResolveExtensionProvider.EP_NAME.getExtensionList(project).any { it is KvueResolveExtensionProvider })
    }

    fun testCrossPackageCallResolvesBeforeKspRuns() {
        component()
        val caller = myFixture.configureByText("Usage.kt", """
            package usage
            import sample.Counter
            import kvue.vdom.RenderGroup
            fun RenderGroup.page() { Counter(step = 2, onChanged = { value, label -> println(label + value) }) }
        """.trimIndent()) as KtFile
        refresh()
        val target = resolveCall(caller)
        assertEquals("Counter", target.name)
        assertEquals(listOf("step", "onChanged", "key", "content"), target.valueParameters.map { it.name })
        assertTrue(target.valueParameters.first().typeReference!!.text.contains("Int?"))
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
        assertNull(myFixture.tempDirFixture.getFile("build/generated/ksp"))
    }

    fun testBindingAliasesResolveAndWritableTargetsHaveNoErrors() {
        bindingComponent()
        myFixture.configureByText("BindingUsage.kt", """
            package usage
            import sample.ModelInput
            import kvue.vdom.RenderGroup
            class State { var value = "before" }
            class PrivateState : kvue.Component() {
                private var value by ref("before")
                fun RenderGroup.render() { ModelInput(vModel = value) }
            }
            fun RenderGroup.page() {
                var local = "before"
                val state = State()
                ModelInput(vModel = local)
                ModelInput(bindModelValue = state.value)
            }
        """.trimIndent())
        refresh()
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
    }

    fun testBindingTargetsAreMarkedReadOnlyInaccessibleOrInvalid() {
        bindingComponent()
        myFixture.configureByText("InvalidBindings.kt", """
            package usage
            import sample.ModelInput
            import kvue.vdom.RenderGroup
            class State { var value = "before"; private set }
            fun RenderGroup.page() {
                val readOnly = "before"
                val state = State()
                ModelInput(vModel = readOnly)
                ModelInput(vModel = state.value)
                ModelInput(vModel = "literal")
            }
        """.trimIndent())
        refresh()
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description.orEmpty() }
        assertTrue(errors.toString(), errors.any { "read-only" in it })
        assertTrue(errors.toString(), errors.any { "not accessible" in it })
        assertTrue(errors.toString(), errors.any { "writable variable or property" in it })
    }

    fun testBindingConflictsAndMissingRequiredPropAreMarked() {
        bindingComponent(required = true)
        myFixture.configureByText("ConflictingBindings.kt", """
            package usage
            import sample.ModelInput
            import kvue.vdom.RenderGroup
            fun RenderGroup.page() {
                var value = "before"
                ModelInput()
                ModelInput(vModel = value, bindModelValue = value)
                ModelInput(modelValue = "explicit", vModel = value)
            }
        """.trimIndent())
        refresh()
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description.orEmpty() }
        assertTrue(errors.toString(), errors.any { "Required prop" in it })
        assertTrue(errors.toString(), errors.any { "one binding alias" in it })
        assertTrue(errors.toString(), errors.any { "cannot be combined" in it })
    }

    private fun bindingComponent(required: Boolean = false) {
        myFixture.addFileToProject("sample/ModelInput.kt", """
            package sample
            import kvue.Component
            import kvue.annotation.*
            @VueComponent
            class ModelInput : Component() {
                @Prop(required = $required) private val modelValue by property("")
                @Emit(name = "update:modelValue") private val changed: (String) -> Unit by event()
            }
        """.trimIndent())
    }

    fun testCompletionOffersTheGeneratedExtensionAndImportsIt() {
        component()
        myFixture.configureByText("Usage.kt", """
            package usage
            import kvue.vdom.RenderGroup
            fun RenderGroup.page() { Coun<caret> }
        """.trimIndent())
        refresh()
        val items = myFixture.complete(CompletionType.BASIC, 2).orEmpty()
        val extension = items.firstOrNull { item ->
            val presentation = LookupElementPresentation()
            item.renderElement(presentation)
            item.lookupString == "Counter" && presentation.tailText.orEmpty().contains("step")
        }
        assertNotNull("Generated extension missing from completion: ${items.map { it.lookupString }}", extension)
        myFixture.lookup.currentItem = extension
        myFixture.finishLookup('\n')
        assertTrue(myFixture.file.text.contains("import sample.Counter"))
    }

    fun testUnsavedPropChangesReplaceTheSignatureAndShadowOnlyMatchingBuildOutput() {
        val source = component()
        val stale = myFixture.addFileToProject("build/generated/ksp/js/jsMain/kotlin/sample/CounterKVueGenerated.kt", """
            package sample
            import kvue.vdom.RenderGroup
            fun RenderGroup.Counter(stale: Boolean) = Unit
        """.trimIndent())
        val original = stale.text
        val caller = myFixture.configureByText("Usage.kt", """
            package usage
            import sample.Counter
            import kvue.vdom.RenderGroup
            fun RenderGroup.page() { Counter(step = 2) }
        """.trimIndent()) as KtFile
        refresh()
        assertEquals("step", resolveCall(caller).valueParameters.first().name)
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(source)!!
            document.setText(document.text.replace("step by property(1)", "amount by property(\"one\")"))
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        refresh()
        assertTrue(myFixture.doHighlighting(HighlightSeverity.ERROR).any { it.description?.contains("step") == true })
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(caller)!!
            document.setText(document.text.replace("Counter(step = 2)", "Counter(amount = \"updated\")"))
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        assertEquals("amount", resolveCall(caller).valueParameters.first().name)
        assertTrue(resolveCall(caller).valueParameters.first().typeReference!!.text.contains("String?"))
        assertEquals(original, stale.text)
        val owner = ReadAction.compute<org.jetbrains.kotlin.analysis.api.projectStructure.KaModule, RuntimeException> { KaModuleProvider.getModule(project, caller, null) }
        val extensions = KaResolveExtensionProvider.EP_NAME.getExtensionList(project).flatMap { it.provideExtensionsFor(owner) }
        assertTrue(extensions.any { it.getShadowedScope().contains(stale.virtualFile) })
        WriteCommandAction.runWriteCommandAction(project) { source.delete() }
        refresh()
        val remaining = allowAnalysisOnEdt { ReadAction.compute<Any?, RuntimeException> {
            PsiTreeUtil.findChildrenOfType(caller, KtCallExpression::class.java).single { it.calleeExpression?.text == "Counter" }
                .calleeExpression!!.mainReference?.resolve()
        } }
        assertNull("A deleted component must not reappear from stale KSP output", remaining)
        assertEquals(original, stale.text)
    }

    fun testInheritedGenericPropsAndTypeAliasesAreResolvedSemantically() {
        val source = myFixture.addFileToProject("sample/Inherited.kt", """
            package sample
            import kvue.Component
            import kvue.annotation.*
            typealias Message = String?
            typealias Handler = (Int, String) -> Unit
            abstract class Base<T> : Component() { @Prop(required = true) private val value by property<T>() }
            @VueComponent class Inherited : Base<String>() {
                @Prop private val note: Message by property(null)
                @Emit(name = "update:modelValue") private val changed: Handler by event()
            }
        """.trimIndent()) as KtFile
        val model = allowAnalysisOnEdt { ReadAction.compute<EditorComponent?, RuntimeException> {
            KotlinComponentReader.read(source.declarations.filterIsInstance<KtClass>().last())
        } }!!.model
        assertEquals("kotlin.String", model.props.first().type.toString())
        assertTrue(model.props.first().required)
        assertTrue(model.props.single { it.name == "note" }.nullable)
        assertEquals("onUpdateModelValue", model.events.single().parameterName)
        assertEquals(listOf("kotlin.Int", "kotlin.String"), model.events.single().arguments.map { it.toString() })
    }

    fun testExternalAnalysisCreatesSchemaAndDslWithoutGeneratedFiles() {
        val caller = myFixture.configureByText("Usage.kt", """
            package usage
            import widgets.Widget
            import kvue.vdom.RenderGroup
            fun RenderGroup.page() { Widget(title = "hello", onChanged = { value, label -> println(label + value) }) }
        """.trimIndent()) as KtFile
        val report = JsonParser.parseString("""
            {"schemaVersion":2,"components":[{"package":"widgets","name":"Widget","module":"widgets","exportName":"Widget",
              "props":[{"name":"title","memberName":"title","kotlin":"String","required":true,"variadic":false},
                       {"name":"note","memberName":"note","kotlin":"String?","required":false,"variadic":false}],
              "events":[{"name":"changed","memberName":"emitChanged","arguments":["Double","String"],"variadic":false}]}],"schemas":[]}
        """.trimIndent()).asJsonObject
        val schema = JsonObject().apply {
            addProperty("package", "widgets")
            addProperty("name", "Widget")
            addProperty("text", """
                package widgets
                import kvue.annotation.*
                @JsComponent(module = "widgets", exportName = "Widget") interface Widget {
                    @Prop(required = true) val title: String
                    @Prop val note: String?
                    @Emit(name = "changed") val emitChanged: (Double, String) -> Unit
                }
            """.trimIndent())
        }
        report.getAsJsonArray("schemas").add(schema)
        val sourceRoot = Path.of(caller.virtualFile.parent.path)
        val external = ExternalSnapshot(listOf(ExternalLibrary(sourceRoot.resolve("kvue.components.json"), listOf(sourceRoot), ExternalComponents.parse(report))))
        val service = project.service<KvueProjectService>()
        val scan = allowAnalysisOnEdt { ReadAction.compute<KvueProjectService.Scan, RuntimeException> { service.scan(external) } }
        assertEmpty(scan.diagnostics)
        WriteAction.run<RuntimeException> { service.publish(scan) }
        val function = resolveCall(caller, "Widget")
        assertEquals(listOf("title", "note", "onChanged", "key", "content"), function.valueParameters.map { it.name })
        assertTrue(function.valueParameters[1].typeReference!!.text.contains("OptionalProp<String?>"))
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
        assertNull(myFixture.tempDirFixture.getFile("build/generated"))
    }

    fun testKotlinJsModuleResolvesVirtualDeclarations() {
        val manager = FacetManager.getInstance(module)
        val facet = WriteAction.compute<org.jetbrains.kotlin.idea.facet.KotlinFacet, RuntimeException> {
            val type = KotlinFacetType.INSTANCE
            val configuration = type.createDefaultConfiguration()
            configuration.settings.useProjectSettings = false
            configuration.settings.compilerArguments = K2JSCompilerArguments()
            configuration.settings.targetPlatform = JsPlatforms.defaultJsPlatform
            val created = manager.createFacet(type, "Kotlin", configuration, null)
            manager.createModifiableModel().apply { addFacet(created); commit() }
            created
        }
        try {
            val stdlib = File(System.getProperty("kvue.test.js.stdlib"))
            PsiTestUtil.addLibrary(module, "kotlin-stdlib-js", stdlib.parent, stdlib.name)
            component()
            val caller = myFixture.configureByText("JsUsage.kt", """
                package usage
                import sample.Counter
                import kvue.vdom.RenderGroup
                fun RenderGroup.page() { Counter(step = 2) }
            """.trimIndent()) as KtFile
            val owner = ReadAction.compute<org.jetbrains.kotlin.analysis.api.projectStructure.KaModule, RuntimeException> { KaModuleProvider.getModule(project, caller, null) }
            assertEquals(JsPlatforms.defaultJsPlatform, owner.targetPlatform)
            refresh()
            assertEquals("step", resolveCall(caller).valueParameters.first().name)
            assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
        } finally {
            WriteAction.run<RuntimeException> { manager.createModifiableModel().apply { removeFacet(facet); commit() } }
        }
    }

    private fun component(): KtFile = myFixture.addFileToProject("sample/Counter.kt", """
        package sample
        import kvue.Component
        import kvue.annotation.*
        @VueComponent class Counter : Component() {
            @Prop private val step by property(1)
            @Emit private val changed: (Int, String) -> Unit by event()
        }
    """.trimIndent()) as KtFile

    private fun refresh() {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val service = project.service<KvueProjectService>()
        val scan = allowAnalysisOnEdt { ReadAction.compute<KvueProjectService.Scan, RuntimeException> { service.scan() } }
        assertEmpty(scan.diagnostics)
        WriteAction.run<RuntimeException> { service.publish(scan) }
    }

    private fun resolveCall(file: KtFile, name: String = "Counter"): KtNamedFunction = allowAnalysisOnEdt {
        ReadAction.compute<KtNamedFunction, RuntimeException> {
            val call = PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java).single { it.calleeExpression?.text == name }
            val target = call.calleeExpression!!.mainReference?.resolve()
            assertTrue("Expected a virtual extension, got $target", target is KtNamedFunction)
            analyze(file) {
                assertEquals((target as KtNamedFunction).valueParameters.map { it.name }, target.symbol.valueParameters.map { it.name.asString() })
            }
            target as KtNamedFunction
        }
    }
}
