package kvue.compiler

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PropCompilerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun optionalPropsWithoutADefaultValueAreRejected() {
        val result = compile("""
            abstract class Base : Component() {
                @Prop private val inherited by property<Int>()
            }
            class Card : Base() {
                @Prop private val title by property<String>()
                @Prop(name = "sub-title") private val subtitle by property<String?>()
                @Prop(required = false) private val explicit by property<Boolean>()
            }
        """)
        assertEquals(ExitCode.COMPILATION_ERROR, result.exit, result.messages)
        for (name in listOf("inherited", "title", "subtitle", "explicit")) {
            assertTrue("KVUE: Optional prop '$name' has no default value" in result.messages, result.messages)
        }
        assertEquals(4, Regex("KVUE: Optional prop").findAll(result.messages).count(), result.messages)
    }

    @Test fun requiredPropsDefaultsAndOtherDelegatesCompile() {
        val result = compile("""
            class Card : Component() {
                @Prop(required = true) private val id by property<String>()
                @Prop(required = true, name = "model-value") private val model by property<String?>()
                @Prop private val count by property(0)
                @Prop private val note by property<String?>(null)
                private val plain by property<String>()
            }
        """)
        assertEquals(ExitCode.OK, result.exit, result.messages)
    }

    private data class Result(val exit: ExitCode, val messages: String)

    private fun compile(source: String): Result {
        val directory = temporary.newFolder()
        val runtime = directory.resolve("Runtime.kt").apply { writeText("""
            package kvue
            abstract class Component {
                fun <T> property(defaultValue: T): PropertyValue<T> = PropertyValue(defaultValue)
                fun <T> property(): PropertyValue<T> = PropertyValue(null)
            }
            class PropertyValue<T>(private val value: Any?) {
                @Suppress("UNCHECKED_CAST")
                operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = value as T
            }
        """.trimIndent()) }
        val sample = directory.resolve("Sample.kt").apply { writeText("""
            import kvue.Component
            import kvue.annotation.Prop
            $source
        """.trimIndent()) }
        val messages = ByteArrayOutputStream()
        val stdlib = kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI().path
        val exit = K2JVMCompiler().exec(PrintStream(messages), "-no-stdlib", "-no-reflect", "-jvm-target", "21", "-classpath", stdlib,
            "-Xplugin=${System.getProperty("kvue.compiler.jar")}", "-d", directory.resolve("classes").absolutePath,
            System.getProperty("kvue.annotations.runtime"), runtime.absolutePath, sample.absolutePath)
        return Result(exit, messages.toString(Charsets.UTF_8))
    }
}
