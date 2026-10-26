package kvue.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComponentCodegenTest {
    @Test fun slotsHaveDedicatedScopesAndPreserveTheNodeType() {
        val model = ComponentModel(ClassName("sample", "Panel"), emptyList(), emptyList(),
            slotNames = listOf("header", "footer", "empty-state", "emptyState"))
        val built = ComponentCodegen.generate(model).single()
        val editor = ComponentCodegen.generate(model, true).single()
        for (file in listOf(built, editor)) {
            val types = file.members.filterIsInstance<TypeSpec>().associateBy { it.name }
            val scopes = types.getValue("PanelVue").typeSpecs.associateBy { it.name }
            assertEquals(ClassName("kvue.vdom", "ComponentContentScope"), scopes.getValue("Content").superclass)
            val slots = scopes.getValue("Slots").funSpecs.associateBy { it.name }
            assertEquals(ClassName("kvue.vdom", "RenderFun"), slots.getValue("header").parameters.single().type)
            assertEquals(ClassName("kvue.vdom", "RenderFun"), slots.getValue("footer").parameters.single().type)
            assertEquals(model.nodeName, types.getValue("PanelNode").funSpecs.single { it.name == "slot" }.returnType)
            val content = file.members.filterIsInstance<FunSpec>().single().parameters.single { it.name == "content" }
            assertEquals(ClassName("kvue.vdom", "ComponentContent").parameterizedBy(model.contentName).copy(nullable = true), content.type)
        }
        assertEquals(4, model.slotMembers.values.toSet().size)
        assertEquals(setOf("PanelVue", "PanelNode", "PanelInstance"),
            model.generatedClassNames.map { it.simpleName }.toSet())
    }

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

    @Test fun controlledPropsExposeBothNamedBindingsAndDefaultModelAlias() {
        val controlled = ComponentModel(ClassName("sample", "Editor"),
            listOf(PropModel("modelValue", "modelValue", STRING, false, true),
                PropModel("title", "title", STRING.copy(nullable = true), true, false)),
            listOf(EventModel("changed", "update:modelValue", listOf(STRING)),
                EventModel("titleChanged", "update:title", listOf(STRING.copy(nullable = true)))))
        val built = ComponentCodegen.generate(controlled).single().members.filterIsInstance<FunSpec>().single()
        val editor = ComponentCodegen.generate(controlled, true).single().members.filterIsInstance<FunSpec>().single()
        assertEquals(built.parameters, editor.parameters)
        assertEquals(listOf("bindModelValue", "vModel", "bindTitle"),
            built.parameters.filter { it.annotations.any { annotation -> annotation.typeName == ClassName("kvue.annotation", "VBind") } }.map { it.name })
        assertTrue(built.parameters.first().defaultValue.toString().contains("requiredBindingProp"))
        assertTrue(built.parameters.find { it.name == "vModel" }!!.defaultValue.toString().contains("omittedBinding"))
        val conflicting = controlled.copy(props = controlled.props + PropModel("bindTitle", "other", STRING, false, false))
        assertEquals(listOf("bindTitle"), conflicting.bindingNameConflicts())
    }
}
