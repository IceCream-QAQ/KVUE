@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package kvue.idea

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.Dynamic
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.WildcardTypeName
import kvue.codegen.*
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.types.*
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.types.Variance

internal data class EditorComponent(
    val model: ComponentModel,
    val origin: SmartPsiElementPointer<out PsiElement>,
    val members: Map<String, SmartPsiElementPointer<out PsiElement>>,
)

/** Called only from a background smart read action, never from a resolve extension callback. */
internal object KotlinComponentReader {
    fun read(declaration: KtClass): EditorComponent? = analyze(declaration) {
        val symbol = declaration.symbol as? KaNamedClassSymbol ?: return@analyze null
        val vue = symbol.annotations.find { it.classId?.asFqNameString() == "kvue.annotation.VueComponent" }
        val js = symbol.annotations.find { it.classId?.asFqNameString() == "kvue.annotation.JsComponent" }
        if (vue == null && js == null) return@analyze null
        require(vue == null || js == null) { "Use either @VueComponent or @JsComponent." }
        require(declaration.parent is KtFile && symbol.typeParameters.isEmpty()) { "Components must be top-level and non-generic." }
        require(symbol.visibility in setOf(KaSymbolVisibility.PUBLIC, KaSymbolVisibility.INTERNAL)) { "Components must be public or internal." }
        val jsImport = js?.let { JsImportModel(it.string("module"), it.string("exportName", "default")) }
        if (jsImport != null) {
            require(declaration.isInterface() && jsImport.module.isNotBlank() && jsImport.exportName.isNotBlank()) { "@JsComponent requires an interface and a non-empty import." }
        } else {
            require(!declaration.isInterface() && !declaration.hasModifier(KtTokens.ABSTRACT_KEYWORD) && !declaration.hasModifier(KtTokens.SEALED_KEYWORD)) { "@VueComponent requires a concrete class." }
            require(symbol.defaultType.allSupertypes.any { (it as? KaClassType)?.classId?.asFqNameString() == "kvue.Component" }) { "@VueComponent must extend kvue.Component." }
            require(symbol.declaredMemberScope.constructors.any { constructor ->
                constructor.visibility in setOf(KaSymbolVisibility.PUBLIC, KaSymbolVisibility.INTERNAL) &&
                    constructor.valueParameters.all { it.hasDefaultValue || it.isVararg }
            }) { "Component constructor must be callable without arguments." }
        }
        val props = mutableListOf<PropModel>()
        val events = mutableListOf<EventModel>()
        val navigation = mutableMapOf<String, SmartPsiElementPointer<out PsiElement>>()
        val pointerManager = SmartPointerManager.getInstance(declaration.project)
        val signatures = (sequenceOf(symbol.defaultType) + symbol.defaultType.allSupertypes)
            .flatMap { ownerType ->
                val classType = ownerType as? KaClassType ?: return@flatMap emptySequence()
                val owner = classType.symbol as? KaNamedClassSymbol ?: return@flatMap emptySequence()
                val substitutions = owner.typeParameters.zip(classType.typeArguments).associate { (parameter, argument) ->
                    parameter to requireNotNull(argument.type) { "Inherited component type must be concrete." }
                }
                val substitutor = createSubstitutor(substitutions)
                owner.declaredMemberScope.callables.filterIsInstance<KaPropertySymbol>().map { it.substitute(substitutor) }
            }
            .distinctBy { it.name.asString() }
            .sortedBy { it.name.asString() }
        for (signature in signatures) {
            val property = signature.symbol
            val prop = property.annotations.find { it.classId?.asFqNameString() == "kvue.annotation.Prop" }
            val emit = property.annotations.find { it.classId?.asFqNameString() == "kvue.annotation.Emit" }
            if (prop == null && emit == null) continue
            require(prop == null || emit == null) { "A property cannot be both @Prop and @Emit." }
            val source = property.psi as? KtProperty
            require(property.isVal && (jsImport != null || source?.hasDelegate() == true)) { "Props and emits require delegated vals." }
            val name = signature.name.asString()
            val type = signature.returnType
            val variadic = (prop ?: emit)!!.boolean("variadic")
            val function = type as? KaFunctionType
            require(!variadic || (jsImport != null && function?.parameterTypes?.size == 1 &&
                (function.parameterTypes.single() as? KaClassType)?.classId?.asFqNameString() == "kotlin.Array")) { "Variadic callbacks require a single array argument on @JsComponent." }
            val nullable = type.isNullable
            if (prop != null) {
                props += PropModel(name, prop.string("name").ifEmpty { name }, toPoet(type), nullable, prop.boolean("required"), variadic)
                source?.let { navigation[name] = pointerManager.createSmartPsiElementPointer(it) }
            } else {
                require(function != null && !function.isSuspend && !function.isReflectType && !function.hasReceiver && !nullable &&
                    (function.returnType as? KaClassType)?.classId?.asFqNameString() == "kotlin.Unit" &&
                    !function.returnType.isNullable) { "@Emit requires a non-null function returning Unit without a receiver." }
                val eventName = emit!!.string("name").ifEmpty { name }
                require(validEventName(eventName)) { "Unsupported event name: $eventName" }
                val event = EventModel(name, eventName, function.parameterTypes.map { toPoet(it) }, variadic)
                events += event
                source?.let { navigation[event.parameterName] = pointerManager.createSmartPsiElementPointer(it) }
            }
        }
        val names = mutableSetOf("key", "content")
        require(props.all { names.add(it.name) } && events.all { names.add(it.parameterName) }) { "Conflicting DSL parameter name." }
        require(events.map { it.eventName }.distinct().size == events.size) { "Duplicate event name." }
        val classId = checkNotNull(symbol.classId)
        EditorComponent(
            ComponentModel(ClassName(classId.packageFqName.asString(), classId.shortClassName.asString()),
                props.sortedBy { !it.required }, events, jsImport,
                if (symbol.visibility == KaSymbolVisibility.INTERNAL) KModifier.INTERNAL else KModifier.PUBLIC),
            pointerManager.createSmartPsiElementPointer(declaration), navigation,
        )
    }

    private fun KaAnnotation.string(name: String, default: String = ""): String =
        ((arguments.find { it.name.asString() == name }?.expression as? KaAnnotationValue.ConstantValue)?.value?.value as? String) ?: default
    private fun KaAnnotation.boolean(name: String): Boolean =
        ((arguments.find { it.name.asString() == name }?.expression as? KaAnnotationValue.ConstantValue)?.value?.value as? Boolean) ?: false

    private fun KaSession.toClassType(type: KaClassType): TypeName {
        val id = type.classId
        val className = ClassName(id.packageFqName.asString(), id.relativeClassName.pathSegments().map { it.asString() })
        val arguments = type.typeArguments.map { projection ->
            val projected = projection.type?.let { toPoet(it) } ?: return@map STAR
            when ((projection as? KaTypeArgumentWithVariance)?.variance) {
                Variance.IN_VARIANCE -> WildcardTypeName.consumerOf(projected)
                Variance.OUT_VARIANCE -> WildcardTypeName.producerOf(projected)
                else -> projected
            }
        }
        return if (arguments.isEmpty()) className else className.parameterizedBy(arguments)
    }

    private fun KaSession.toPoet(type: KaType): TypeName = with(type) {
        require(this !is KaErrorType) { "Component type is not resolved yet." }
        val converted = abbreviation?.let { toClassType(it) } ?: when (this) {
            is KaFunctionType -> if (isReflectType) toClassType(this) else LambdaTypeName.get(receiver = receiverType?.let { toPoet(it) },
                parameters = parameterTypes.map { ParameterSpec.unnamed(toPoet(it)) }, returnType = toPoet(returnType)).copy(suspending = isSuspend)
            is KaClassType -> toClassType(this)
            is KaDynamicType -> Dynamic
            is KaFlexibleType -> toPoet(upperBound)
            else -> error("Cannot expose an unresolved or non-denotable component type: ${this::class.simpleName}")
        }
        converted.copy(nullable = abbreviation?.isMarkedNullable ?: isMarkedNullable)
    }
}
