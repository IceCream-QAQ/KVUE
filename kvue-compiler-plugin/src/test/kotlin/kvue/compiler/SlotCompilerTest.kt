package kvue.compiler

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlotCompilerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun extractsBothDeclarationFormsAndKeepsEveryRenderBodyLazy() {
        val result = compile("""
            var value = "first"
            val calls = mutableListOf<String>()
            fun runTest(): String {
                val node = Panel {
                    slot("header") { calls += "header"; text(value) }
                    calls += "default"
                    text(value)
                    slots.footer { calls += "footer"; text("foot") }
                }
                check(calls.isEmpty()) { calls.toString() }
                check(node.render("header") == listOf("first"))
                check(calls == listOf("header")) { calls.toString() }
                check(node.render("footer") == listOf("foot"))
                check(calls == listOf("header", "footer"))
                value = "second"
                check(node.render("default") == listOf("second"))
                check(calls == listOf("header", "footer", "default"))
                check(node.render("header") == listOf("second"))
                return "OK"
            }
        """)
        assertEquals(ExitCode.OK, result.exit, result.messages)
        assertEquals("OK", result.run())
    }

    @Test fun supportsStoredBodiesNestedComponentsAndHtmlNamesWithoutCreatingAnEmptyDefault() {
        val result = compile("""
            var nested: ElementNode? = null
            fun RenderGroup.header(content: RenderFun) { text("<header>"); with(content) { invoke() } }
            fun runTest(): String {
                val content = ComponentContent<PanelContent> {
                    slots.header { text("named header") }
                    header { text("HTML header") }
                    nested = Panel { slot("footer") { text("nested footer") } }
                }
                val node = Panel(content)
                check(nested == null)
                check(node.render("header") == listOf("named header"))
                check(nested == null)
                check(node.render("default") == listOf("<header>", "HTML header"))
                check(nested!!.render("footer") == listOf("nested footer"))
                check("default" !in nested!!.slots)
                val namedOnly = Panel { slots.header { text("header only") } }
                check(namedOnly.slots.keys == setOf("header")) { namedOnly.slots.keys.toString() }
                val old = RenderFun { text("reusable") }
                check(Panel(old.asComponentContent()).render("default") == listOf("reusable"))
                return "OK"
            }
        """)
        assertEquals(ExitCode.OK, result.exit, result.messages)
        assertEquals("OK", result.run())
    }

    @Test fun rejectsAmbiguousNestedDeclarationsAndReferencesToDefaultLocals() {
        val nested = compile("""
            fun runTest() {
                Panel { if (true) slot("header") { text("nested") } }
            }
        """)
        assertEquals(ExitCode.COMPILATION_ERROR, nested.exit, nested.messages)
        assertTrue(nested.messages.contains("Declare slots directly"), nested.messages)
        val local = compile("""
            fun runTest() {
                Panel {
                    val value = "default local"
                    slot("header") { text(value) }
                    text(value)
                }
            }
        """)
        assertEquals(ExitCode.COMPILATION_ERROR, local.exit, local.messages)
        assertTrue(local.messages.contains("Move shared declarations"), local.messages)
        val helper = compile("""
            fun PanelContent.helper() { slot("header") { text("hidden declaration") } }
            fun runTest() { Panel { helper() } }
        """)
        assertEquals(ExitCode.COMPILATION_ERROR, helper.exit, helper.messages)
        assertTrue(helper.messages.contains("Declare slots directly"), helper.messages)
    }

    @Test fun typedSlotReceiversSurviveExtractionAndNestedComponentCopies() {
        val result = compile("""
            fun runTest(): String {
                val node = Panel {
                    slots.item { text(this.label); Panel { slots.item { text(label) } }.also { check(it.render("item", mapOf("label" to "nested")) == listOf("nested")) } }
                    text("ordinary")
                }
                check(node.render("item", mapOf("label" to "first")) == listOf("first"))
                check(node.render("item", mapOf("label" to "second")) == listOf("second"))
                check(node.render("default") == listOf("ordinary"))
                return "OK"
            }
        """)
        assertEquals(ExitCode.OK, result.exit, result.messages)
        assertEquals("OK", result.run())
    }

    private data class Result(val exit: ExitCode, val messages: String, val output: File) {
        fun run(): Any? = URLClassLoader(arrayOf(output.toURI().toURL()), SlotCompilerTest::class.java.classLoader).use {
            it.loadClass("SampleKt").getMethod("runTest").invoke(null)
        }
    }

    private fun compile(source: String): Result {
        val directory = temporary.newFolder()
        val render = directory.resolve("Render.kt").apply { writeText("""
            package kvue.vdom
            annotation class KVueDsl
            open class RenderGroup {
                private val values = mutableListOf<String>()
                fun text(value: String) { values += value }
                fun addElement(value: String) { values += value }
                internal fun result(): List<String> = values
            }
            open class SlotScope(val slotProps: Any?) : RenderGroup()
            fun interface SlotContent<in S : SlotScope> { operator fun S.invoke() }
            fun interface RenderFun : SlotContent<SlotScope> {
                operator fun RenderGroup.invoke()
                override fun SlotScope.invoke() { (this as RenderGroup).invoke() }
            }
            fun evaluate(block: RenderFun): List<String> {
                val group = RenderGroup()
                with(block) { group.invoke() }
                return group.result()
            }
        """.trimIndent()) }
        val node = directory.resolve("Node.kt").apply { writeText("""
            package kvue.vdom.knode
            import kvue.vdom.*
            class ElementNode {
                val slots = linkedMapOf<String, MutableList<(Any?) -> List<String>>>()
                fun slot(name: String, content: SlotContent<SlotScope>): ElementNode = slot(name, ::SlotScope, content)
                fun <S : SlotScope> slot(name: String, factory: (Any?) -> S, content: SlotContent<S>): ElementNode = apply {
                    slots.getOrPut(name) { mutableListOf() }.add { props ->
                        val scope = factory(props)
                        with(content) { scope.invoke() }
                        scope.result()
                    }
                }
                fun render(name: String, props: Any? = null): List<String> = slots[name].orEmpty().flatMap { it(props) }
            }
        """.trimIndent()) }
        val sample = directory.resolve("Sample.kt").apply { writeText("""
            import kvue.annotation.SlotDeclaration
            import kvue.vdom.*
            import kvue.vdom.knode.ElementNode
            class PanelContent : ComponentContentScope() { val slots = PanelSlots(this) }
            class ItemScope(props: Any?) : SlotScope(props) { val label: String get() = (slotProps as Map<*, *>)["label"] as String }
            class PanelSlots(private val scope: ComponentContentScope) {
                @SlotDeclaration("header") fun header(content: SlotContent<SlotScope>) = scope.slot("header", content)
                @SlotDeclaration("footer") fun footer(content: SlotContent<SlotScope>) = scope.slot("footer", content)
                @SlotDeclaration("item") fun item(content: SlotContent<ItemScope>) = scope.slot("item", ::ItemScope, content)
            }
            fun Panel(content: ComponentContent<PanelContent>? = null): ElementNode = ElementNode().also {
                bindComponentContent(it, content, ::PanelContent)
            }
            $source
        """.trimIndent()) }
        val output = directory.resolve("classes")
        val messages = ByteArrayOutputStream()
        val stdlib = kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI().path
        val exit = K2JVMCompiler().exec(PrintStream(messages), "-no-stdlib", "-no-reflect", "-jvm-target", "21", "-classpath", stdlib,
            "-Xplugin=${System.getProperty("kvue.compiler.jar")}", "-d", output.absolutePath,
            System.getProperty("kvue.content.runtime"), System.getProperty("kvue.annotations.runtime"),
            render.absolutePath, node.absolutePath, sample.absolutePath)
        return Result(exit, messages.toString(Charsets.UTF_8), output)
    }
}
