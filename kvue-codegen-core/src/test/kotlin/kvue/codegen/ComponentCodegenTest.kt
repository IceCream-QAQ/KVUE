package kvue.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.STRING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComponentCodegenTest {
    private val model = ComponentModel(
        ClassName("sample", "Counter"),
        listOf(PropModel("title", "title", STRING, false, true),
            PropModel("step", "step-size", INT, false, false),
            PropModel("note", "note", STRING.copy(nullable = true), true, false)),
        listOf(EventModel("changed", "update:modelValue", listOf(INT, STRING))),
        visibility = KModifier.INTERNAL,
    )

    @Test fun editorAndBuildHaveTheSameSignatures() {
        for (component in listOf(model, model.copy(jsImport = JsImportModel("widgets", "Counter")))) {
            val built = ComponentCodegen.generate(component).last().members.filterIsInstance<FunSpec>().single()
            val editor = ComponentCodegen.generate(component, declarationsOnly = true).single().members.filterIsInstance<FunSpec>().single()
            assertEquals(built.parameters, editor.parameters)
            assertEquals(built.receiverType, editor.receiverType)
            assertEquals(built.returnType, editor.returnType)
            assertEquals(built.modifiers, editor.modifiers)
            assertTrue(editor.parameters[2].type.toString().contains("OptionalProp<kotlin.String?>"))
            assertEquals("onUpdateModelValue", editor.parameters[3].name)
            assertEquals("kvue.OptionalProp.Omitted", editor.parameters[2].defaultValue.toString())
        }
    }

    @Test fun virtualFilesContainNoJsImportsOrRuntimeImplementations() {
        val text = ComponentCodegen.generate(model.copy(jsImport = JsImportModel("widgets", "default")), true).single().toString()
        assertTrue(text.contains("object CounterVue"))
        assertTrue(text.contains("fun RenderGroup.Counter"))
        assertFalse(text.contains("@JsModule"))
        assertFalse(text.contains("rawCounter"))
        assertFalse(text.contains("this.addElement"))
    }
}
