package kvue.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeName

/** Build and editor adapters own source handles; this model contains no compiler/IDE objects. */
data class ComponentModel(
    val className: ClassName,
    val props: List<PropModel>,
    val events: List<EventModel>,
    val jsImport: JsImportModel? = null,
    val visibility: KModifier = KModifier.PUBLIC,
) {
    val generatedName = ClassName(className.packageName, className.simpleName + "Vue")
    val fileName = className.simpleName + "KVueGenerated"

    val bindings: List<BindingModel> get() = props.mapNotNull { prop ->
        val event = events.singleOrNull { it.parameterName == eventParameterName("update:${prop.wireName}") }
        if (event == null || event.arguments.isEmpty()) null
        else BindingModel(prop, event)
    }

    fun bindingNameConflicts(): List<String> {
        val names = (props.map { it.name } + events.map { it.parameterName } + listOf("key", "content")).toMutableSet()
        return bindings.flatMap { it.parameterNames }.filter { !names.add(it) }
    }
}

data class BindingModel(val prop: PropModel, val event: EventModel) {
    val parameterNames: List<String> get() = listOf("bind" + prop.name.replaceFirstChar(Char::uppercaseChar)) +
        if (prop.wireName in listOf("modelValue", "model-value")) listOf("vModel") else emptyList()
}

data class JsImportModel(val module: String, val exportName: String)

data class PropModel(
    val name: String,
    val wireName: String,
    val type: TypeName,
    val nullable: Boolean,
    val required: Boolean,
    val variadic: Boolean = false,
)

data class EventModel(
    val propertyName: String,
    val eventName: String,
    val arguments: List<TypeName>,
    val variadic: Boolean = false,
) {
    val parameterName: String get() = eventParameterName(eventName)
    val listenerName: String get() = "on" + eventName.replaceFirstChar(Char::uppercaseChar)
}

fun eventParameterName(name: String): String =
    "on" + name.split('-', ':').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

fun validEventName(name: String): Boolean = name.split('-', ':').all { segment ->
    segment.isNotEmpty() && segment.all { it.isLetterOrDigit() || it == '_' }
}
