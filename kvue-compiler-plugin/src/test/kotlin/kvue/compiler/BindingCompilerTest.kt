package kvue.compiler

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import kotlin.test.*

class BindingCompilerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun bindsLocalsMembersDelegatesNullableValuesAndNamedProps() {
        val result = compile("""
            class State {
                var text = "initial"
                var delegated by Delegate("delegate")
                fun bindPrivate(): () -> Unit {
                    var local = "local"
                    Input(vModel = local)
                    update!!("changed")
                    check(local == "changed")
                    Input(vModel = delegated)
                    update!!("delegated changed")
                    check(delegated == "delegated changed")
                    return { Input(vModel = text) }
                }
            }
            fun runTest(): String {
                val state = State()
                state.bindPrivate()()
                check(read == "initial")
                update!!("member changed")
                check(state.text == "member changed")
                var nullable: String? = null
                NullableInput(bindModelValue = nullable)
                check(nullableRead == null)
                nullableUpdate!!("set")
                check(nullable == "set")
                nullableUpdate!!(null)
                check(nullable == null)
                var shown = false
                NamedInput(bindVisible = shown)
                boolUpdate!!(true)
                check(shown)
                BroadInput(vModel = shown)
                broadUpdate!!(false)
                check(!shown)
                VariadicInput(vModel = shown)
                restUpdate!!(arrayOf(true, "extra"))
                check(shown)
                var localDelegate by Delegate("local delegate")
                Input(vModel = localDelegate)
                update!!("local delegate changed")
                check(localDelegate == "local delegate changed")
                if (nullable != null) { Input(vModel = nullable) }
                return "OK"
            }
        """)
        assertEquals(ExitCode.OK, result.exit, result.messages)
        assertEquals("OK", result.run())
    }

    @Test fun preservesArgumentOrderAndEvaluatesReceiversOnce() {
        val result = compile("""
            val trace = mutableListOf<String>()
            class State {
                var value: String = "start"
                    get() { trace += "get"; return field }
                    set(value) { trace += "set"; field = value }
            }
            val state = State()
            fun receiver(): State { trace += "receiver"; return state }
            fun label(): String { trace += "label"; return "label" }
            fun runTest(): String {
                Input(vModel = receiver().value, label = label())
                check(trace == listOf("receiver", "get", "label")) { trace.toString() }
                update!!("next")
                check(trace == listOf("receiver", "get", "label", "set")) { trace.toString() }
                trace.clear()
                Input(label = label(), vModel = receiver().value)
                check(trace == listOf("label", "receiver", "get")) { trace.toString() }
                return "OK"
            }
        """)
        assertEquals(ExitCode.OK, result.exit, result.messages)
        assertEquals("OK", result.run())
    }

    @Test fun rejectsReadOnlyInaccessibleAndInvalidTargetsAndConflicts() {
        for ((source, diagnostic) in listOf(
            "val immutable = \"x\"; Input(vModel = immutable)" to "read-only",
            "Input(vModel = \"literal\")" to "writable",
            "Input(vModel = makeValue())" to "writable",
            "val box = PrivateSetter(); Input(vModel = box.value)" to "not accessible",
            "var value = \"x\"; Input(modelValue = value, vModel = value)" to "cannot be combined",
            "var value = \"x\"; Input(bindModelValue = value, vModel = value)" to "only one binding alias",
            "var count = 1; WrongUpdate(vModel = count)" to "cannot be assigned",
            "RequiredInput()" to "Required prop",
        )) {
            val result = compile("""
                class PrivateSetter { var value = "x"; private set }
                fun makeValue() = "x"
                fun runTest() { $source }
            """)
            assertEquals(ExitCode.COMPILATION_ERROR, result.exit, result.messages)
            assertTrue(result.messages.contains("KVUE:") && result.messages.contains(diagnostic), result.messages)
        }
    }

    @Test fun requiredControlledPropsAcceptEitherExplicitValueOrBinding() {
        val result = compile("""
            fun runTest(): String {
                var value = "bound"
                RequiredInput(vModel = value)
                check(read == "bound")
                update!!("updated")
                check(value == "updated")
                RequiredInput(modelValue = "explicit")
                check(read == "explicit")
                return "OK"
            }
        """)
        assertEquals(ExitCode.OK, result.exit, result.messages)
        assertEquals("OK", result.run())
    }

    @Test fun respectsInternalSettersAcrossModulesAndFriendModules() {
        val library = compile("class InternalState { var value = \"value\"; internal set }")
        assertEquals(ExitCode.OK, library.exit, library.messages)
        val source = "fun runTest() { val state = InternalState(); Input(vModel = state.value) }"
        val denied = compile(source, listOf(library.output))
        assertEquals(ExitCode.COMPILATION_ERROR, denied.exit, denied.messages)
        assertTrue("not accessible" in denied.messages, denied.messages)
        val direct = compile("fun runTest() { InternalState().value = \"assigned\" }", listOf(library.output), arrayOf("-Xfriend-paths=${library.output.absolutePath}"))
        assertEquals(ExitCode.OK, direct.exit, direct.messages)
        val friend = compile(source, listOf(library.output), arrayOf("-Xfriend-paths=${library.output.absolutePath}"))
        assertEquals(ExitCode.OK, friend.exit, friend.messages)
    }

    @Test fun preservesSuperSetterDispatchAndNestedProtectedAccess() {
        val result = compile("""
            open class Base {
                open var value = "base"
                    protected set
            }
            class Derived : Base() {
                var overrideWrites = 0
                override var value = "derived"
                    set(value) { overrideWrites++; field = value }
                fun bindSuper() {
                    Input(vModel = super.value)
                    update!!("base updated")
                    check(super.value == "base updated")
                    check(overrideWrites == 0)
                }
            }
            class Nested : Base() {
                inner class Editor {
                    fun bind() { Input(vModel = this@Nested.value) }
                }
            }
            fun runTest(): String {
                Derived().bindSuper()
                val nested = Nested()
                nested.Editor().bind()
                update!!("nested updated")
                check(nested.value == "nested updated")
                return "OK"
            }
        """)
        assertEquals(ExitCode.OK, result.exit, result.messages)
        assertEquals("OK", result.run())
    }

    private data class Result(val exit: ExitCode, val messages: String, val output: java.io.File) {
        fun run(): Any? = URLClassLoader(arrayOf(output.toURI().toURL()), BindingCompilerTest::class.java.classLoader).use {
            it.loadClass("SampleKt").getMethod("runTest").invoke(null)
        }
    }

    private fun compile(source: String, libraries: List<java.io.File> = emptyList(), options: Array<String> = emptyArray()): Result {
        val dir = temporary.newFolder()
        val annotation = dir.resolve("Annotations.kt").apply { writeText("""
            package kvue.annotation
            @Target(AnnotationTarget.VALUE_PARAMETER)
            @Retention(AnnotationRetention.BINARY)
            annotation class VBind(val prop: String, val event: String, val variadic: Boolean = false)
            @Target(AnnotationTarget.VALUE_PARAMETER)
            @Retention(AnnotationRetention.BINARY)
            annotation class RequiredBindingProp
        """.trimIndent()) }
        val runtime = dir.resolve("Runtime.kt").apply { writeText("""
            package kvue
            sealed interface OptionalProp<out T> {
                data object Omitted: OptionalProp<Nothing>
                data class Value<T>(val value: T): OptionalProp<T>
            }
            fun <T> provided(value: T): OptionalProp<T> = OptionalProp.Value(value)
            fun bindingUpdateValue(arguments: Array<Any?>): Any? = arguments.first()
        """.trimIndent()) }
        val sample = dir.resolve("Sample.kt").apply { writeText("""
            import kvue.annotation.*
            var read: String? = null
            var update: ((String) -> Unit)? = null
            var nullableRead: String? = null
            var nullableUpdate: ((String?) -> Unit)? = null
            var boolUpdate: ((Boolean) -> Unit)? = null
            var broadUpdate: ((Any) -> Unit)? = null
            var restUpdate: ((Array<Any?>) -> Unit)? = null
            class Delegate<T>(var value: T) {
                operator fun getValue(owner: Any?, prop: kotlin.reflect.KProperty<*>): T = value
                operator fun setValue(owner: Any?, prop: kotlin.reflect.KProperty<*>, value: T) { this.value = value }
            }
            fun Input(modelValue: String? = null, onUpdateModelValue: ((String) -> Unit)? = null,
                label: String = "", @VBind("modelValue", "onUpdateModelValue") bindModelValue: String? = null,
                @VBind("modelValue", "onUpdateModelValue") vModel: String? = null) { read = modelValue; update = onUpdateModelValue }
            fun NullableInput(modelValue: kvue.OptionalProp<String?> = kvue.OptionalProp.Omitted, onUpdateModelValue: ((String?) -> Unit)? = null,
                @VBind("modelValue", "onUpdateModelValue") bindModelValue: String? = null) {
                nullableRead = (modelValue as kvue.OptionalProp.Value).value; nullableUpdate = onUpdateModelValue
            }
            fun NamedInput(visible: Boolean = false, onUpdateVisible: ((Boolean) -> Unit)? = null,
                @VBind("visible", "onUpdateVisible") bindVisible: Boolean = false) { boolUpdate = onUpdateVisible }
            fun BroadInput(modelValue: Any? = null, onUpdateModelValue: ((Any) -> Unit)? = null,
                @VBind("modelValue", "onUpdateModelValue") vModel: Any? = null) { broadUpdate = onUpdateModelValue }
            fun VariadicInput(modelValue: Any? = null, onUpdateModelValue: ((Array<Any?>) -> Unit)? = null,
                @VBind("modelValue", "onUpdateModelValue", variadic = true) vModel: Any? = null) { restUpdate = onUpdateModelValue }
            fun WrongUpdate(modelValue: Int = 0, onUpdateModelValue: ((String) -> Unit)? = null,
                @VBind("modelValue", "onUpdateModelValue") vModel: Int = 0) {}
            fun RequiredInput(@RequiredBindingProp modelValue: String = error("required"), onUpdateModelValue: ((String) -> Unit)? = null,
                @VBind("modelValue", "onUpdateModelValue") vModel: String = "") { read = modelValue; update = onUpdateModelValue }
            $source
        """.trimIndent()) }
        val output = dir.resolve("classes")
        val messages = ByteArrayOutputStream()
        val stdlib = kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI().path
        val classpath = (listOf(stdlib) + libraries.map { it.absolutePath }).joinToString(java.io.File.pathSeparator)
        val exit = K2JVMCompiler().exec(PrintStream(messages), "-no-stdlib", "-no-reflect", "-jvm-target", "21", "-classpath", classpath, *options,
            "-Xplugin=${System.getProperty("kvue.compiler.jar")}", "-d", output.absolutePath,
            annotation.absolutePath, runtime.absolutePath, sample.absolutePath)
        return Result(exit, messages.toString(Charsets.UTF_8), output)
    }
}
