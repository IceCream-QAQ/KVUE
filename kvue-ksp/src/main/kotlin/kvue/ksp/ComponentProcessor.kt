package kvue.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Visibility
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import kvue.codegen.ComponentCodegen
import kvue.codegen.ComponentModel
import kvue.codegen.PropModel
import kvue.codegen.EventModel
import kvue.codegen.JsImportModel
import kvue.codegen.validEventName

class ComponentProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        ComponentProcessor(environment.codeGenerator, environment.logger, environment.options["kvue.outputSubdirectory"])
}

private class ComponentProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val outputSubdirectory: String?,
) : SymbolProcessor {
    private val processed = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()
        (resolver.getSymbolsWithAnnotation(COMPONENT_ANNOTATION) +
            resolver.getSymbolsWithAnnotation(JS_COMPONENT_ANNOTATION)).forEach { symbol ->
            val component = symbol as? KSClassDeclaration ?: return@forEach
            val qualifiedName = component.qualifiedName?.asString() ?: return@forEach
            if (qualifiedName in processed) return@forEach
            if (!component.validate(enableNewFeatures = false)) {
                deferred += component
                return@forEach
            }
            processed += qualifiedName
            val model = readComponent(component, resolver) ?: return@forEach
            generate(model)
        }
        return deferred
    }

    @OptIn(com.google.devtools.ksp.KspExperimental::class)
    private fun readComponent(component: KSClassDeclaration, resolver: Resolver): ParsedComponent? {
        var valid = true
        fun reject(message: String, node: KSNode = component) {
            logger.error("KVUE: $message", node)
            valid = false
        }

        val jsAnnotation = component.annotation(JS_COMPONENT_ANNOTATION)
        val jsImport = jsAnnotation?.let {
            JsImportModel(it.argument("module") as String, it.argument("exportName") as String)
        }
        if (jsImport != null && component.annotation(COMPONENT_ANNOTATION) != null) {
            reject("Use either @VueComponent or @JsComponent, not both.")
        }
        if (jsImport != null && (jsImport.module.isBlank() || jsImport.exportName.isBlank())) {
            reject("@JsComponent requires a non-empty module and exportName.")
        }

        if (jsImport != null) {
            if (component.classKind != ClassKind.INTERFACE || component.parentDeclaration != null || component.typeParameters.isNotEmpty()) {
                reject("@JsComponent requires a top-level interface without type parameters.")
            }
        } else {
            if (component.classKind != ClassKind.CLASS || component.parentDeclaration != null ||
                Modifier.ABSTRACT in component.modifiers || Modifier.SEALED in component.modifiers || component.typeParameters.isNotEmpty()
            ) {
                reject("@VueComponent requires a concrete, top-level class without type parameters.")
            }
        }
        if (component.getVisibility() !in setOf(Visibility.PUBLIC, Visibility.INTERNAL)) {
            reject("A generated component must be public or internal.")
        }
        if (jsImport == null && component.getAllSuperTypes().none { it.declaration.qualifiedName?.asString() == "kvue.Component" }) {
            reject("@VueComponent classes must extend kvue.Component.")
        }
        if (jsImport == null && component.getConstructors().none {
                it.getVisibility() in setOf(Visibility.PUBLIC, Visibility.INTERNAL) &&
                    it.parameters.all { parameter -> parameter.hasDefault || parameter.isVararg }
            }
        ) {
            reject("The component needs an accessible constructor callable without arguments.")
        }
        if (!valid) return null

        val className = component.toClassName()
        val generatedName = ClassName(className.packageName, className.simpleName + "Vue")
        if (resolver.getClassDeclarationByName(resolver.getKSNameFromString(generatedName.canonicalName)) != null) {
            reject("Generated name '${generatedName.canonicalName}' is already declared.")
        }
        val conflictingFunction = resolver.getDeclarationsFromPackage(className.packageName)
            .filterIsInstance<KSFunctionDeclaration>()
            .any { declaration ->
                declaration.simpleName.asString() == className.simpleName &&
                    declaration.extensionReceiver?.resolve()?.declaration?.qualifiedName?.asString() in
                    setOf("kvue.vdom.RenderGroup", "kvue.vdom.RenderContext")
            }
        if (conflictingFunction) reject("A RenderGroup.${className.simpleName} extension is already declared.")

        val memberSources = mutableMapOf<String, KSPropertyDeclaration>()
        val props = mutableListOf<PropModel>()
        val events = mutableListOf<EventModel>()
        val declarations = (sequenceOf(component.asStarProjectedType()) + component.getAllSuperTypes())
            .map { it.expandAliases(resolver) }
            .flatMap { ownerType ->
                (ownerType.declaration as? KSClassDeclaration)?.declarations
                    ?.filterIsInstance<KSPropertyDeclaration>()
                    ?.map { it to ownerType }.orEmpty()
            }
            .distinctBy { it.first.simpleName.asString() }
            .sortedBy { it.first.simpleName.asString() }
            .toList()
        for ((property, ownerType) in declarations) {
            memberSources[property.simpleName.asString()] = property
            val propAnnotation = property.annotation(PROP_ANNOTATION)
            val emitAnnotation = property.annotation(EMIT_ANNOTATION)
            if (propAnnotation == null && emitAnnotation == null) continue
            if (propAnnotation != null && emitAnnotation != null) {
                reject("A property cannot be both @Prop and @Emit.", property)
                continue
            }
            if (property.isMutable || (jsImport == null && !property.isDelegated())) {
                reject("@Prop and @Emit require a delegated val, using property(...) or event().", property)
                continue
            }
            val declaredType = property.asMemberOf(ownerType)
            if (declaredType.isError) {
                reject("Cannot resolve the type of '${property.simpleName.asString()}' in this component.", property)
                continue
            }
            val type = declaredType.expandAliases(resolver)
            val variadic = (propAnnotation ?: emitAnnotation)!!.argument("variadic") as? Boolean ?: false
            if (variadic && jsImport == null) {
                reject("Variadic JS callbacks are only supported on @JsComponent schemas.", property)
                continue
            }
            if (variadic && (!type.isFunctionType || type.arguments.size != 2 ||
                    type.arguments.first().type?.resolve()?.declaration?.qualifiedName?.asString() != "kotlin.Array")
            ) {
                reject("A variadic JS callback must take one Array<Any?> argument.", property)
                continue
            }
            if (propAnnotation != null) {
                val name = property.simpleName.asString()
                props += PropModel(
                    name,
                    (propAnnotation.argument("name") as? String).orEmpty().ifEmpty { name },
                    declaredType.toTypeName(),
                    type.nullability == Nullability.NULLABLE,
                    propAnnotation.argument("required") as? Boolean ?: false,
                    variadic,
                )
                continue
            }

            if (!type.isFunctionType || type.isSuspendFunctionType || type.isMarkedNullable ||
                type.annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == "kotlin.ExtensionFunctionType" }
            ) {
                reject("@Emit requires a non-null, non-suspend function type without a receiver.", property)
                continue
            }
            val arguments = type.arguments.map { it.type?.resolve() }
            if (arguments.isEmpty() || arguments.any { it == null } ||
                arguments.last()?.declaration?.qualifiedName?.asString() != "kotlin.Unit" ||
                arguments.last()?.isMarkedNullable == true
            ) {
                reject("@Emit functions must return Unit and have concrete argument types.", property)
                continue
            }
            val propertyName = property.simpleName.asString()
            val eventName = (emitAnnotation!!.argument("name") as? String).orEmpty().ifEmpty { propertyName }
            if (!validEventName(eventName)) {
                reject("Event names must consist of letters, digits or underscores, separated by '-' or ':'.", property)
                continue
            }
            events += EventModel(
                propertyName,
                eventName,
                arguments.dropLast(1).map { it!!.toTypeName() },
                variadic,
            )
        }

        val parameterNames = mutableSetOf("key", "content")
        props.forEach {
            if (!parameterNames.add(it.name)) reject("DSL parameter '${it.name}' conflicts with another parameter or reserved name.", memberSources[it.name] ?: component)
        }
        val eventNames = mutableSetOf<String>()
        events.forEach {
            if (!parameterNames.add(it.parameterName)) reject("DSL parameter '${it.parameterName}' conflicts with a prop or another event.", memberSources[it.propertyName] ?: component)
            if (!eventNames.add(it.eventName)) reject("Event '${it.eventName}' is declared more than once.", memberSources[it.propertyName] ?: component)
        }
        if (!valid) return null
        val model = ComponentModel(className, props.sortedBy { !it.required }, events, jsImport,
            if (component.getVisibility() == Visibility.INTERNAL) KModifier.INTERNAL else KModifier.PUBLIC)
        model.bindingNameConflicts().forEach { reject("Binding parameter '$it' conflicts with an existing DSL parameter.") }
        if (!valid) return null
        return ParsedComponent(
            model,
            (listOf(component.containingFile) + declarations.map { it.first.containingFile }).filterNotNull().distinct().toTypedArray(),
        )
    }

    private fun generate(parsed: ParsedComponent) {
        require(outputSubdirectory == null || outputSubdirectory == "__kvue__") { "Unsupported KVUE output subdirectory" }
        ComponentCodegen.generate(parsed.model).forEach { file ->
            val dependencies = Dependencies(false, *parsed.origins)
            val output = if (outputSubdirectory == null) {
                codeGenerator.createNewFile(dependencies, file.packageName, file.name)
            } else {
                val path = listOf(outputSubdirectory, file.packageName.replace('.', '/'), file.name)
                    .filter { it.isNotEmpty() }.joinToString("/")
                codeGenerator.createNewFileByPath(dependencies, path, "kt")
            }
            output.bufferedWriter().use { file.writeTo(it) }
        }
    }
}

private data class ParsedComponent(val model: ComponentModel, val origins: Array<KSFile>)
private fun KSAnnotated.annotation(name: String): KSAnnotation? =
    annotations.firstOrNull { it.annotationType.resolve().declaration.qualifiedName?.asString() == name }

private fun KSAnnotation.argument(name: String): Any? = arguments.firstOrNull { it.name?.asString() == name }?.value

/** Expand aliases for semantic checks while retaining their public names in prop signatures. */
private fun KSType.expandAliases(
    resolver: Resolver,
    substitutions: Map<KSTypeParameter, KSTypeArgument> = emptyMap(),
): KSType {
    val parameter = declaration as? KSTypeParameter
    val replacement = substitutions[parameter]?.type?.resolve()
    if (replacement != null && replacement != this) {
        val expanded = replacement.expandAliases(resolver, substitutions)
        return if (isMarkedNullable) expanded.makeNullable() else expanded
    }
    val expandedArguments = arguments.map { argument ->
        val argumentType = argument.type?.resolve() ?: return@map argument
        val replacementArgument = substitutions[argumentType.declaration as? KSTypeParameter]
        if (replacementArgument != null && replacementArgument.type == null) return@map replacementArgument
        resolver.getTypeArgument(
            resolver.createKSTypeReferenceFromKSType(argumentType.expandAliases(resolver, substitutions)),
            argument.variance,
        )
    }
    val alias = declaration as? KSTypeAlias
    val expanded = if (alias != null) {
        alias.type.resolve().expandAliases(resolver, substitutions + alias.typeParameters.zip(expandedArguments))
    } else {
        if (expandedArguments.isEmpty()) this else replace(expandedArguments)
    }
    return if (isMarkedNullable) expanded.makeNullable() else expanded
}

private const val COMPONENT_ANNOTATION = "kvue.annotation.VueComponent"
private const val JS_COMPONENT_ANNOTATION = "kvue.annotation.JsComponent"
private const val PROP_ANNOTATION = "kvue.annotation.Prop"
private const val EMIT_ANNOTATION = "kvue.annotation.Emit"
