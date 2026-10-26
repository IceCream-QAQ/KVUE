@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package kvue.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtSourcelessDiagnosticFactory
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.rendering.BaseSourcelessDiagnosticRendererFactory
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.FqName

/** An omitted optional prop reads the default given to property(default); without one it would only fail during render. */
internal class PropIrExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        for (file in moduleFragment.files) file.acceptChildrenVoid(object : IrElementVisitorVoid() {
            override fun visitElement(element: IrElement) { element.acceptChildrenVoid(this) }
            override fun visitProperty(declaration: IrProperty) {
                val delegate = missingDefault(declaration)
                if (delegate != null) {
                    val entry = file.fileEntry
                    val offset = delegate.startOffset.coerceAtLeast(0)
                    pluginContext.diagnosticReporter.report(PropErrors.ERROR,
                        "KVUE: Optional prop '${declaration.name}' has no default value. Use property(default), or mark it @Prop(required = true).",
                        CompilerMessageLocation.create(entry.name, entry.getLineNumber(offset) + 1, entry.getColumnNumber(offset) + 1, null))
                }
                super.visitProperty(declaration)
            }
        })
    }

    /** Returns the property() call of an optional prop. Other delegates are left to the runtime check. */
    private fun missingDefault(property: IrProperty): IrCall? {
        if (!property.isDelegated) return null
        val prop = property.getAnnotation(PROP) ?: return null
        // `required` is Prop's first parameter; an omitted argument keeps its false default.
        if ((prop.arguments.getOrNull(0) as? IrConst)?.value == true) return null
        val delegate = property.backingField?.initializer?.expression as? IrCall ?: return null
        val function = delegate.symbol.owner.let { it.resolveFakeOverride() ?: it }
        val isPropertyWithoutDefault = function.name.asString() == "property" &&
            function.parentClassOrNull?.fqNameWhenAvailable == COMPONENT &&
            function.parameters.none { it.kind == IrParameterKind.Regular }
        return delegate.takeIf { isPropertyWithoutDefault }
    }
}

private val PROP = FqName("kvue.annotation.Prop")
private val COMPONENT = FqName("kvue.Component")
private object PropErrors : BaseSourcelessDiagnosticRendererFactory() {
    val ERROR = KtSourcelessDiagnosticFactory("KVUE_PROP_ERROR", Severity.ERROR, this)
    override val MAP by KtDiagnosticFactoryToRendererMap("KVUE") { it.put(ERROR, MESSAGE_PLACEHOLDER) }
}
