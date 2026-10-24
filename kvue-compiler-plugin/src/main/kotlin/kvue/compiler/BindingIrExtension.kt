@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package kvue.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.diagnostics.KtSourcelessDiagnosticFactory
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.rendering.BaseSourcelessDiagnosticRendererFactory
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.isSubtypeOf
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class BindingIrExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        moduleFragment.transformChildren(BindingTransformer(pluginContext, moduleFragment), null)
    }
}

private class BindingTransformer(
    private val context: IrPluginContext,
    private val module: IrModuleFragment,
) : IrElementTransformerVoidWithContext() {
    private val insertions = mutableMapOf<IrVariable, MutableList<IrVariable>>()
    private val typeSystem = IrTypeSystemContextImpl(context.irBuiltIns)
    private val localDelegates = mutableMapOf<org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol, IrLocalDelegatedProperty>()

    override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty): IrStatement {
        localDelegates[declaration.getter.symbol] = declaration
        return super.visitLocalDelegatedProperty(declaration)
    }

    override fun visitBlock(expression: IrBlock): IrExpression {
        super.visitBlock(expression)
        applyInsertions(expression.statements)
        return expression
    }

    override fun visitBlockBody(body: IrBlockBody): IrBody {
        super.visitBlockBody(body)
        applyInsertions(body.statements)
        return body
    }

    private fun applyInsertions(statements: MutableList<IrStatement>) {
        val expanded = statements.flatMap { statement ->
            if (statement is IrVariable) insertions.remove(statement).orEmpty() + statement else listOf(statement)
        }
        statements.clear()
        statements.addAll(expanded)
    }

    private data class Binding(val parameter: IrValueParameter, val prop: IrValueParameter, val event: IrValueParameter, val variadic: Boolean)

    override fun visitCall(expression: IrCall): IrExpression {
        super.visitCall(expression)
        val parameters = expression.symbol.owner.parameters
        val bindings = parameters.mapNotNull { parameter ->
            val annotation = parameter.getAnnotation(FqName("kvue.annotation.VBind")) ?: return@mapNotNull null
            val names = annotation.arguments.filterNotNull().mapNotNull { (it as? IrConst)?.value as? String }
            if (names.size != 2) return@mapNotNull null
            Binding(parameter, parameters.single { it.name.asString() == names[0] }, parameters.single { it.name.asString() == names[1] },
                (annotation.arguments.getOrNull(2) as? IrConst)?.value == true)
        }
        if (bindings.isEmpty()) return expression
        val supplied = bindings.filter { expression.arguments[it.parameter.indexInParameters] != null }
        var invalid = false
        fun reject(argument: IrExpression, message: String) { invalid = true; error(argument, message) }
        for (prop in parameters.filter { it.hasAnnotation(FqName("kvue.annotation.RequiredBindingProp")) }) {
            if (expression.arguments[prop.indexInParameters] == null && supplied.none { it.prop == prop }) {
                reject(expression, "Required prop '${prop.name}' needs a value or a binding.")
            }
        }
        for (binding in supplied) {
            val argument = expression.arguments[binding.parameter.indexInParameters]!!
            if (supplied.count { it.prop == binding.prop } > 1) reject(argument, "Use only one binding alias for '${binding.prop.name}'.")
            if (expression.arguments[binding.prop.indexInParameters] != null || expression.arguments[binding.event.indexInParameters] != null) {
                reject(argument, "'${binding.parameter.name}' cannot be combined with '${binding.prop.name}' or '${binding.event.name}'.")
            }
        }
        if (invalid || supplied.isEmpty()) return expression
        val builder = DeclarationIrBuilder(context, currentScope!!.scope.scopeOwnerSymbol, expression.startOffset, expression.endOffset)
        return builder.irBlock(resultType = expression.type) {
            val setters = mutableMapOf<Binding, IrExpression>()
            // FIR already introduces temporaries for reordered named arguments. Preserve these and
            // stage the remaining arguments in their original evaluation order before remapping.
            for (parameter in parameters) {
                val argument = expression.arguments[parameter.indexInParameters] ?: continue
                val binding = supplied.find { it.parameter == parameter }
                if (binding != null) {
                    val eventType = binding.event.type.makeNotNull() as IrSimpleType
                    val eventArguments = eventType.arguments.dropLast(1).map { it.typeOrNull!! }
                    val before = mutableListOf<IrVariable>()
                    val setter = makeSetter(argument, eventType, eventArguments, before, builder, binding.variadic)
                    if (setter == null) { invalid = true; continue }
                    before.forEach { +it }
                    setters[binding] = setter
                }
                val temporary = irTemporary(argument, "kvueArgument")
                expression.arguments[parameter.indexInParameters] = irGet(temporary)
            }
            if (!invalid) {
                for (binding in supplied) {
                    var value = expression.arguments[binding.parameter.indexInParameters]!!
                    if (binding.prop.type.classFqName?.asString() == "kvue.OptionalProp") {
                        val wrappedType = (binding.prop.type as IrSimpleType).arguments.single().typeOrNull!!
                        val provided = this@BindingTransformer.context.referenceFunctions(CallableId(FqName("kvue"), Name.identifier("provided"))).single()
                        value = irCall(provided, binding.prop.type).apply {
                            typeArguments[0] = wrappedType
                            arguments[0] = value
                        }
                    }
                    expression.arguments[binding.prop.indexInParameters] = value
                    expression.arguments[binding.event.indexInParameters] = setters.getValue(binding)
                    expression.arguments[binding.parameter.indexInParameters] = null
                }
            }
            +expression
        }
    }

    private fun makeSetter(
        argument: IrExpression,
        functionType: IrType,
        eventTypes: List<IrType>,
        before: MutableList<IrVariable>,
        outer: DeclarationIrBuilder,
        variadic: Boolean,
    ): IrExpression? {
        var target = argument
        var anchor: IrVariable? = null
        while (target is IrGetValue) {
            val variable = target.symbol.owner as? IrVariable ?: break
            if (variable.origin != IrDeclarationOrigin.IR_TEMPORARY_VARIABLE || variable.initializer == null) break
            anchor = variable
            target = variable.initializer!!
        }
        while (target is IrTypeOperatorCall && target.operator in listOf(IrTypeOperator.IMPLICIT_CAST, IrTypeOperator.IMPLICIT_NOTNULL, IrTypeOperator.IMPLICIT_DYNAMIC_CAST)) target = target.argument
        val eventType = if (variadic) context.irBuiltIns.anyNType else eventTypes.firstOrNull() ?: return null
        val targetType = target.type
        if (!eventType.isSubtypeOf(targetType, typeSystem) && !eventType.isAny() && !eventType.isNullableAny()) {
            error(argument, "Update value '${eventType.render()}' cannot be assigned to '${targetType.render()}'.")
            return null
        }
        fun capture(receiver: IrExpression): IrExpression {
            val variable = buildVariable(currentDeclarationParent, receiver.startOffset, receiver.endOffset,
                IrDeclarationOrigin.IR_TEMPORARY_VARIABLE, Name.identifier("kvueReceiver"), receiver.type).apply { initializer = receiver }
            if (anchor != null) insertions.getOrPut(anchor) { mutableListOf() }.add(variable) else before.add(variable)
            return outer.irGet(variable)
        }
        val write: (DeclarationIrBuilder, IrExpression) -> IrExpression = when (target) {
            is IrGetValue -> {
                val variable = target.symbol.owner as? IrVariable
                if (variable?.isVar != true) { error(argument, "A binding needs a writable variable or property; '${target.symbol.owner.name}' is read-only."); return null }
                { builder, value -> builder.irSet(variable, value) }
            }
            is IrCall -> {
                val getter = target
                val property = getter.symbol.owner.correspondingPropertySymbol?.owner
                val local = localDelegates[getter.symbol]
                val setter = property?.setter ?: local?.setter
                if (setter == null || (property?.isVar ?: local?.isVar) != true) { error(argument, "A binding needs a writable property, not a value or function result."); return null }
                if (local == null && !setterIsAccessible(setter, getter)) { error(argument, "Setter for '${property?.name}' is not accessible here."); return null }
                val receivers = getter.symbol.owner.parameters.filter { it.kind != IrParameterKind.Regular }.map { parameter ->
                    val receiver = getter.arguments[parameter.indexInParameters]
                    receiver?.let { capture(it).also { captured -> getter.arguments[parameter.indexInParameters] = captured } }
                };
                { builder, value ->
                    builder.irCall(setter.symbol).apply {
                        superQualifierSymbol = getter.superQualifierSymbol
                        for (index in typeArguments.indices) typeArguments[index] = getter.typeArguments.getOrNull(index)
                        var receiverIndex = 0
                        setter.parameters.forEach { parameter ->
                            arguments[parameter.indexInParameters] = if (parameter.kind == IrParameterKind.Regular) value
                                else receivers[receiverIndex++]?.deepCopyWithSymbols()
                        }
                    }
                }
            }
            is IrGetField -> {
                val field = target.symbol.owner
                if (field.isFinal) { error(argument, "A binding needs a writable field."); return null }
                val receiver = target.receiver?.let(::capture)
                target.receiver = receiver
                { builder, value -> builder.irSetField(receiver?.deepCopyWithSymbols(), field, value) }
            }
            else -> { error(argument, "A binding needs a writable variable or property; expressions and safe calls cannot be assigned."); return null }
        }
        val function = context.irFactory.buildFun {
            name = Name.special("<anonymous>")
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
            visibility = DescriptorVisibilities.LOCAL
            returnType = context.irBuiltIns.unitType
        }.apply {
            parent = currentDeclarationParent!!
            eventTypes.forEachIndexed { index, type -> addValueParameter("value$index", type) }
        }
        val builder = DeclarationIrBuilder(context, function.symbol, argument.startOffset, argument.endOffset)
        function.body = builder.irBlockBody {
            var value: IrExpression = irGet(function.parameters.first())
            if (variadic) {
                val firstValue = this@BindingTransformer.context.referenceFunctions(CallableId(FqName("kvue"), Name.identifier("bindingUpdateValue"))).single()
                value = irCall(firstValue).apply { arguments[0] = value }
            }
            if (!eventType.isSubtypeOf(targetType, typeSystem)) value = irAs(value, targetType)
            +write(builder, value)
        }
        return IrFunctionExpressionImpl(argument.startOffset, argument.endOffset, functionType, function, IrStatementOrigin.LAMBDA)
    }

    private fun setterIsAccessible(candidate: IrSimpleFunction, getter: IrCall): Boolean {
        val setter = candidate.resolveFakeOverride() ?: candidate
        return when (setter.visibility) {
        DescriptorVisibilities.PUBLIC -> true
        DescriptorVisibilities.INTERNAL -> {
            val ownerMetadata = setter.metadata ?: getter.symbol.owner.correspondingPropertySymbol?.owner?.metadata ?: getter.symbol.owner.metadata
            val currentModule = (currentFile.metadata as? FirMetadataSource)?.fir?.moduleData
            // Deserialized accessors may carry no IR metadata. Resolve their owning declaration in
            // the current FIR session so Kotlin's regular/friend binary modules stay distinct.
            val provider = currentModule?.session?.symbolProvider
            val ownerModule = (ownerMetadata as? FirMetadataSource)?.fir?.moduleData
                ?: setter.parentClassOrNull?.classId?.let { provider?.getClassLikeSymbolByClassId(it)?.moduleData }
                ?: setter.correspondingPropertySymbol?.owner?.fqNameWhenAvailable?.let { name ->
                    provider?.getTopLevelPropertySymbols(name.parent(), name.shortName())?.singleOrNull()?.moduleData
                }
            setter.fileOrNull in module.files || (ownerModule != null && currentModule != null &&
                (ownerModule == currentModule || ownerModule in currentModule.friendDependencies || ownerModule in currentModule.allDependsOnDependencies))
        }
        DescriptorVisibilities.PRIVATE, DescriptorVisibilities.PRIVATE_TO_THIS -> {
            val owner = setter.parent
            if (owner is IrFile) owner == currentFile
            else generateSequence(currentDeclarationParent as? IrDeclaration) { it.parent as? IrDeclaration }.any { it == owner }
        }
        DescriptorVisibilities.PROTECTED -> {
            val owner = setter.parent as? IrClass
            val enclosing = generateSequence(currentDeclarationParent as? IrDeclaration) { it.parent as? IrDeclaration }.filterIsInstance<IrClass>()
            val declaring = if (owner?.isCompanion == true) owner.parentClassOrNull else owner
            declaring != null && enclosing.any { current ->
                current.defaultType.isSubtypeOf(declaring.defaultType, typeSystem) &&
                    (owner?.isCompanion == true || getter.dispatchReceiver?.type?.isSubtypeOf(current.defaultType, typeSystem) == true)
            }
        }
        else -> false
        }
    }

    private fun error(expression: IrExpression, message: String) {
        val entry = currentFile.fileEntry
        val offset = expression.startOffset.coerceAtLeast(0)
        context.diagnosticReporter.report(BindingErrors.ERROR, "KVUE: $message",
            CompilerMessageLocation.create(entry.name, entry.getLineNumber(offset) + 1, entry.getColumnNumber(offset) + 1, null))
    }
}

private object BindingErrors : BaseSourcelessDiagnosticRendererFactory() {
    val ERROR = KtSourcelessDiagnosticFactory("KVUE_BINDING_ERROR", Severity.ERROR, this)
    override val MAP by KtDiagnosticFactoryToRendererMap("KVUE") { it.put(ERROR, MESSAGE_PLACEHOLDER) }
}
