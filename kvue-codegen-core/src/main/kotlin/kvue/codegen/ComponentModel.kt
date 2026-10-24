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
