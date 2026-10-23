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
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.Dynamic
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName

class ComponentProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        ComponentProcessor(environment.codeGenerator, environment.logger)
}

private class ComponentProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
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
    private fun readComponent(component: KSClassDeclaration, resolver: Resolver): ComponentModel? {
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
                    property,
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
            val segments = eventName.split('-', ':')
            if (segments.any { it.isEmpty() || !it.all { character -> character.isLetterOrDigit() || character == '_' } }) {
                reject("Event names must consist of letters, digits or underscores, separated by '-' or ':'.", property)
                continue
            }
            events += EventModel(
                propertyName,
                eventName,
                "on" + segments.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) },
                "on" + eventName.replaceFirstChar(Char::uppercaseChar),
                arguments.dropLast(1).map { it!!.toTypeName() },
                variadic,
                property,
            )
        }

        val parameterNames = mutableSetOf("key", "content")
        props.forEach {
            if (!parameterNames.add(it.name)) reject("DSL parameter '${it.name}' conflicts with another parameter or reserved name.", it.source)
        }
        val eventNames = mutableSetOf<String>()
        events.forEach {
            if (!parameterNames.add(it.parameterName)) reject("DSL parameter '${it.parameterName}' conflicts with a prop or another event.", it.source)
            if (!eventNames.add(it.eventName)) reject("Event '${it.eventName}' is declared more than once.", it.source)
        }
        if (!valid) return null
        return ComponentModel(component, className, generatedName, props.sortedBy { !it.required }, events, jsImport)
    }

    private fun generate(model: ComponentModel) {
        val visibility = if (model.source.getVisibility() == Visibility.INTERNAL) KModifier.INTERNAL else KModifier.PUBLIC
        val definition = if (model.jsImport != null) importedDefinition(model) else nativeDefinition(model)

        val backingObject = TypeSpec.objectBuilder(model.generatedName)
            .addModifiers(visibility)
            .addProperty(
                PropertySpec.builder("definition", if (model.jsImport != null) JS_DEFINITION else DEFINITION.parameterizedBy(model.className))
                    .initializer(definition)
                    .build()
            ).build()

        val dsl = FunSpec.builder(model.className.simpleName)
            .addModifiers(visibility)
            .receiver(RENDER_GROUP)
            .returns(ELEMENT_NODE)
        model.props.forEach { prop ->
            val type = when {
                prop.required -> prop.type
                prop.nullable -> OPTIONAL_PROP.parameterizedBy(prop.type)
                else -> prop.type.copy(nullable = true)
            }
            val parameter = ParameterSpec.builder(prop.name, type)
            if (!prop.required) {
                if (prop.nullable) parameter.defaultValue("%T.Omitted", OPTIONAL_PROP)
                else parameter.defaultValue("null")
            }
            dsl.addParameter(parameter.build())
        }
        model.events.forEach { event ->
            val callbackType = LambdaTypeName.get(
                parameters = event.arguments.map { ParameterSpec.unnamed(it) },
                returnType = UNIT,
            ).copy(nullable = true)
            dsl.addParameter(ParameterSpec.builder(event.parameterName, callbackType).defaultValue("null").build())
        }
        dsl.addParameter(ParameterSpec.builder("key", ANY.copy(nullable = true)).defaultValue("null").build())
        dsl.addParameter(ParameterSpec.builder("content", RENDER_FUN.copy(nullable = true)).defaultValue("null").build())

        val usedNames = model.props.map { it.name } + model.events.map { it.parameterName } + listOf("key", "content")
        val node = generateSequence("node") { it + "_" }.first { it !in usedNames }
        dsl.addStatement("val %N = %T(%T.definition)", node, ELEMENT_NODE, model.generatedName)
        model.props.forEach { prop ->
            val value = if (prop.required || !prop.nullable) CodeBlock.of("%N", prop.name) else CodeBlock.of("%N.value", prop.name)
            val forwarded = if (prop.variadic) CodeBlock.of("%M(%L)", VARIADIC_CALLBACK, value) else value
            when {
                prop.required -> dsl.addStatement("%N.property(%S, %L)", node, prop.wireName, forwarded)
                prop.nullable -> {
                    dsl.beginControlFlow("if (%N is %T.Value<*>)", prop.name, OPTIONAL_PROP)
                        .addStatement("%N.property(%S, %L)", node, prop.wireName, forwarded)
                        .endControlFlow()
                }
                else -> {
                    dsl.beginControlFlow("if (%N != null)", prop.name)
                        .addStatement("%N.property(%S, %L)", node, prop.wireName, forwarded)
                        .endControlFlow()
                }
            }
        }
        model.events.forEach { event ->
            val value = if (event.variadic) CodeBlock.of("%M(%N)", VARIADIC_CALLBACK, event.parameterName) else CodeBlock.of("%N", event.parameterName)
            dsl.beginControlFlow("if (%N != null)", event.parameterName)
                .addStatement("%N.property(%S, %L)", node, event.listenerName, value)
                .endControlFlow()
        }
        dsl.beginControlFlow("if (key != null)").addStatement("%N.property(%S, key)", node, "key").endControlFlow()
        dsl.beginControlFlow("if (content != null)").addStatement("%N.slot(%S, content)", node, "default").endControlFlow()
        dsl.addStatement("this.addElement(%N)", node).addStatement("return %N", node)

        val file = FileSpec.builder(model.className.packageName, model.className.simpleName + "KVueGenerated")
            .addFileComment("Generated by KVUE KSP. Do not edit.")
            .addType(backingObject)
            .addFunction(dsl.build())
            .build()
        writeFile(file, model)
    }

    private fun importedDefinition(model: ComponentModel): CodeBlock {
        val jsImport = checkNotNull(model.jsImport)
        val importsPackage = listOf(model.className.packageName, "imports").filter { it.isNotEmpty() }.joinToString(".")
        val rawName = "raw" + model.className.simpleName
        val file = FileSpec.builder(importsPackage, model.className.simpleName + "JsImport")
        val raw = PropertySpec.builder(rawName, Dynamic).addModifiers(KModifier.INTERNAL, KModifier.EXTERNAL)
        if (jsImport.exportName == "default" || jsImport.exportName == "export=") {
            raw.addAnnotation(AnnotationSpec.builder(JS_MODULE).addMember("%S", jsImport.module).build())
            raw.addAnnotation(JS_NON_MODULE)
        } else {
            file.addAnnotation(AnnotationSpec.builder(JS_MODULE).useSiteTarget(AnnotationSpec.UseSiteTarget.FILE).addMember("%S", jsImport.module).build())
            file.addAnnotation(AnnotationSpec.builder(JS_NON_MODULE).useSiteTarget(AnnotationSpec.UseSiteTarget.FILE).build())
            raw.addAnnotation(AnnotationSpec.builder(JS_NAME).addMember("%S", jsImport.exportName).build())
        }
        file.addProperty(raw.build())
        writeFile(file.build(), model)
        val imported = CodeBlock.of("%M", MemberName(importsPackage, rawName))
        val value = if (jsImport.exportName == "default") CodeBlock.of("%M(%L)", DEFAULT_EXPORT, imported) else imported
        return CodeBlock.of("%T(%L, %S)", JS_DEFINITION, value, "${jsImport.module}:${jsImport.exportName}")
    }

    private fun nativeDefinition(model: ComponentModel): CodeBlock {
        val definition = CodeBlock.builder()
            .add("%T(\n", DEFINITION)
            .indent()
            .add("name = %S,\n", model.className.simpleName)
            .add("props = arrayOf(")
        model.props.forEachIndexed { index, prop ->
            if (index > 0) definition.add(", ")
            definition.add("%T(%S, required = %L, propertyName = %S)", COMPONENT_PROP, prop.wireName, prop.required, prop.name)
        }
        definition.add("),\nemits = arrayOf(")
        model.events.forEachIndexed { index, event ->
            if (index > 0) definition.add(", ")
            definition.add("%S", event.eventName)
        }
        definition.add("),\nfactory = { %T() },\n", model.className)
        if (model.events.isNotEmpty()) {
            definition.add("bindEvents = { context ->\n").indent()
            model.events.forEachIndexed { eventIndex, event ->
                val functionType = LambdaTypeName.get(
                    parameters = event.arguments.map { ParameterSpec.unnamed(it) },
                    returnType = UNIT,
                )
                definition.add("val event%L: %T = { ", eventIndex, functionType)
                if (event.arguments.isNotEmpty()) {
                    event.arguments.forEachIndexed { index, type ->
                        if (index > 0) definition.add(", ")
                        definition.add("arg%L: %T", index, type)
                    }
                    definition.add(" -> ")
                }
                definition.add("context.emit(%S", event.eventName)
                event.arguments.indices.forEach { definition.add(", arg%L", it) }
                definition.add(") }\n")
            }
            definition.add("mapOf<String, Any>(\n").indent()
            model.events.forEachIndexed { index, event ->
                definition.add("%S to event%L,\n", event.propertyName, index)
            }
            definition.unindent().add(")\n").unindent().add("},\n")
        }
        definition.unindent().add(")")
        return definition.build()
    }

    private fun writeFile(file: FileSpec, model: ComponentModel) {
        val origins = (listOf(model.source.containingFile) +
            model.props.map { it.source.containingFile } + model.events.map { it.source.containingFile })
            .filterNotNull().distinct().toTypedArray()
        codeGenerator.createNewFile(Dependencies(false, *origins), file.packageName, file.name)
            .bufferedWriter().use { file.writeTo(it) }
    }
}

private data class ComponentModel(
    val source: KSClassDeclaration,
    val className: ClassName,
    val generatedName: ClassName,
    val props: List<PropModel>,
    val events: List<EventModel>,
    val jsImport: JsImportModel?,
)

private data class JsImportModel(val module: String, val exportName: String)

private data class PropModel(
    val name: String,
    val wireName: String,
    val type: TypeName,
    val nullable: Boolean,
    val required: Boolean,
    val variadic: Boolean,
    val source: KSPropertyDeclaration,
)

private data class EventModel(
    val propertyName: String,
    val eventName: String,
    val parameterName: String,
    val listenerName: String,
    val arguments: List<TypeName>,
    val variadic: Boolean,
    val source: KSPropertyDeclaration,
)

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
private val DEFINITION = ClassName("kvue", "ComponentDefinition")
private val COMPONENT_PROP = ClassName("kvue", "ComponentProp")
private val OPTIONAL_PROP = ClassName("kvue", "OptionalProp")
private val RENDER_GROUP = ClassName("kvue.vdom", "RenderGroup")
private val RENDER_FUN = ClassName("kvue.vdom", "RenderFun")
private val ELEMENT_NODE = ClassName("kvue.vdom.knode", "ElementNode")
private val JS_DEFINITION = ClassName("kvue", "JsComponentDefinition")
private val JS_MODULE = ClassName("kotlin.js", "JsModule")
private val JS_NON_MODULE = ClassName("kotlin.js", "JsNonModule")
private val JS_NAME = ClassName("kotlin.js", "JsName")
private val DEFAULT_EXPORT = MemberName("kvue", "jsDefaultExport")
private val VARIADIC_CALLBACK = MemberName("kvue", "variadicJsCallback")
