@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package kvue.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtSourcelessDiagnosticFactory
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.rendering.BaseSourcelessDiagnosticRendererFactory
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Split named declarations from default rendering before the binding transform sees either body. */
internal class SlotIrExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val transformer = SlotTransformer(pluginContext)
        moduleFragment.transformChildren(transformer, null)
        if (!transformer.hasErrors) validateSlotUsage(moduleFragment, pluginContext)
    }
}

private class SlotTransformer(private val context: IrPluginContext) : IrElementTransformerVoidWithContext() {
    var hasErrors = false
        private set
    override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
        super.visitTypeOperator(expression)
        if (expression.operator != IrTypeOperator.SAM_CONVERSION || expression.typeOperand.classFqName?.asString() != CONTENT) return expression
        val functionExpression = expression.argument as? IrFunctionExpression ?: return expression
        val function = functionExpression.function
        val body = function.body as? IrBlockBody ?: return expression
        val allDeclarations = mutableListOf<IrCall>()
        body.acceptChildrenVoid(object : IrElementVisitorVoid() {
            override fun visitElement(element: IrElement) { element.acceptChildrenVoid(this) }
            override fun visitTypeOperator(expression: IrTypeOperatorCall) {
                // A nested component owns its own declarations and has already been transformed.
                if (expression.typeOperand.classFqName?.asString() != CONTENT) super.visitTypeOperator(expression)
            }
            override fun visitCall(expression: IrCall) {
                if (expression.symbol.owner.hasAnnotation(SLOT)) allDeclarations += expression
                super.visitCall(expression)
            }
        })
        if (allDeclarations.isEmpty()) return expression

        val declarations = body.statements.mapIndexedNotNull { index, statement ->
            directDeclaration(statement)?.let { index to it }
        }.toMap()
        val misplaced = allDeclarations.filter { it !in declarations.values }
        if (misplaced.isNotEmpty()) {
            misplaced.forEach { error(it, "Declare slots directly in the component content block. For conditional registration, configure the returned node with apply { if (...) slot(...) { ... } }.") }
            return expression
        }
        val defaultIndexes = body.statements.indices.filter { it !in declarations && !unitReturn(body.statements[it]) }
        val defaultSymbols = mutableSetOf<IrSymbol>()
        for (index in defaultIndexes) body.statements[index].acceptVoid(object : IrElementVisitorVoid() {
            override fun visitElement(element: IrElement) { element.acceptChildrenVoid(this) }
            override fun visitDeclaration(declaration: IrDeclarationBase) {
                defaultSymbols += declaration.symbol
                super.visitDeclaration(declaration)
            }
        })
        var invalid = false
        for (index in declarations.keys) body.statements[index].acceptVoid(object : IrElementVisitorVoid() {
            override fun visitElement(element: IrElement) { element.acceptChildrenVoid(this) }
            override fun visitDeclarationReference(expression: IrDeclarationReference) {
                if (expression.symbol in defaultSymbols) {
                    invalid = true
                    error(expression, "A named slot cannot capture a declaration from implicit default content. Move shared declarations before the component call, or into the individual slot.")
                }
                super.visitDeclarationReference(expression)
            }
        })
        if (invalid) return expression

        val defaultFunction = if (defaultIndexes.isEmpty()) null else function.deepCopyWithSymbols(function).apply {
            val copiedBody = this.body as IrBlockBody
            val kept = defaultIndexes.map { copiedBody.statements[it] }
            copiedBody.statements.clear()
            copiedBody.statements.addAll(kept)
        }
        val registrations = declarations.keys.map { unwrapReturn(body.statements[it]) }
        body.statements.clear()
        body.statements.addAll(registrations)
        val scopeType = (expression.typeOperand as IrSimpleType).arguments.single().typeOrNull!!
        if (defaultFunction != null) {
            val defaultBody = IrTypeOperatorCallImpl(expression.startOffset, expression.endOffset, expression.type,
                IrTypeOperator.SAM_CONVERSION, expression.typeOperand,
                IrFunctionExpressionImpl(functionExpression.startOffset, functionExpression.endOffset,
                    functionExpression.type, defaultFunction, IrStatementOrigin.LAMBDA))
            val builder = DeclarationIrBuilder(context, function.symbol, expression.startOffset, expression.endOffset)
            body.statements += builder.irCall(runtimeFunction("componentDefaultContent")).apply {
                typeArguments[0] = scopeType
                arguments[0] = builder.irGet(function.parameters.single())
                arguments[1] = defaultBody
            }
        }
        val builder = DeclarationIrBuilder(context, currentScope!!.scope.scopeOwnerSymbol, expression.startOffset, expression.endOffset)
        return builder.irCall(runtimeFunction("preparedComponentContent"), expression.type).apply {
            typeArguments[0] = scopeType
            arguments[0] = expression
        }
    }

    private fun runtimeFunction(name: String) =
        context.referenceFunctions(CallableId(FqName("kvue.vdom"), Name.identifier(name))).single()

    private fun unwrapReturn(statement: IrStatement): IrStatement =
        if (statement is IrReturn) statement.value else statement

    private fun unitReturn(statement: IrStatement): Boolean {
        val value = unwrapReturn(statement)
        return value is IrGetObjectValue && value.type.isUnit()
    }

    private fun directDeclaration(statement: IrStatement): IrCall? {
        val value = unwrapReturn(statement)
        if (value is IrCall && value.symbol.owner.hasAnnotation(SLOT)) return value
        // FIR can stage reordered named arguments in a block before the call.
        if (value is IrBlock && value.statements.dropLast(1).all {
                it is IrVariable && it.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE
            }) return value.statements.lastOrNull()?.let(::directDeclaration)
        return null
    }

    private fun error(expression: IrExpression, message: String) {
        hasErrors = true
        val entry = currentFile.fileEntry
        val offset = expression.startOffset.coerceAtLeast(0)
        context.diagnosticReporter.report(SlotErrors.ERROR, "KVUE: $message",
            CompilerMessageLocation.create(entry.name, entry.getLineNumber(offset) + 1, entry.getColumnNumber(offset) + 1, null))
    }
}

/** Reject helper/function-reference cases that cannot be extracted, rather than silently dropping slots. */
private fun validateSlotUsage(module: IrModuleFragment, context: IrPluginContext) {
    val configurations = mutableSetOf<IrFunction>()
    fun lambda(expression: IrExpression?): IrFunction? = when (expression) {
        is IrFunctionExpression -> expression.function
        is IrTypeOperatorCall -> lambda(expression.argument)
        else -> null
    }
    // Collect after all copying so nested configurations copied into a default slot are included.
    module.acceptChildrenVoid(object : IrElementVisitorVoid() {
        override fun visitElement(element: IrElement) { element.acceptChildrenVoid(this) }
        override fun visitCall(expression: IrCall) {
            if (expression.symbol.owner.fqNameWhenAvailable?.asString() == "kvue.vdom.preparedComponentContent") {
                lambda(expression.arguments.firstOrNull())?.let(configurations::add)
            }
            super.visitCall(expression)
        }
    })
    module.acceptChildrenVoid(object : IrElementVisitorVoid() {
        private var file: IrFile? = null
        private var function: IrFunction? = null
        override fun visitElement(element: IrElement) { element.acceptChildrenVoid(this) }
        override fun visitFile(declaration: IrFile) {
            val previous = file
            file = declaration
            declaration.acceptChildrenVoid(this)
            file = previous
        }
        override fun visitFunction(declaration: IrFunction) {
            val previous = function
            function = declaration
            declaration.acceptChildrenVoid(this)
            function = previous
        }
        override fun visitCall(expression: IrCall) {
            if (expression.symbol.owner.hasAnnotation(SLOT) && function !in configurations &&
                function?.hasAnnotation(SLOT) != true) {
                val entry = checkNotNull(file).fileEntry
                val offset = expression.startOffset.coerceAtLeast(0)
                context.diagnosticReporter.report(SlotErrors.ERROR,
                    "KVUE: Declare slots directly in component content, not in a separate helper or render callback.",
                    CompilerMessageLocation.create(entry.name, entry.getLineNumber(offset) + 1, entry.getColumnNumber(offset) + 1, null))
            }
            super.visitCall(expression)
        }
    })
}

private const val CONTENT = "kvue.vdom.ComponentContent"
private val SLOT = FqName("kvue.annotation.SlotDeclaration")
private object SlotErrors : BaseSourcelessDiagnosticRendererFactory() {
    val ERROR = KtSourcelessDiagnosticFactory("KVUE_SLOT_ERROR", Severity.ERROR, this)
    override val MAP by KtDiagnosticFactoryToRendererMap("KVUE") { it.put(ERROR, MESSAGE_PLACEHOLDER) }
}
