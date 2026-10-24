@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package kvue.idea

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.singleVariableAccessCall
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.psi.*

/** Binding parameters carry the same metadata in KSP output and K2 virtual declarations. */
class KvueBindingAnnotator : Annotator {
    private data class BindingArgument(val argument: KtExpression, val prop: String, val event: String, val variadic: Boolean)
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is KtCallExpression) return
        val issues = analyze(element) {
            val call = element.resolveToCall()?.singleFunctionCallOrNull() ?: return@analyze emptyList()
            val parameters = call.signature.valueParameters
            if (parameters.none { parameter -> parameter.symbol.annotations.any { it.classId?.asFqNameString() == BIND } }) return@analyze emptyList()
            val mapping = call.valueArgumentMapping
            val suppliedNames = mapping.values.map { it.name.asString() }.toSet()
            val suppliedBindings = mapping.mapNotNull { (argument, parameter) ->
                val annotation = parameter.symbol.annotations.find { it.classId?.asFqNameString() == BIND } ?: return@mapNotNull null
                BindingArgument(argument, annotation.string("prop"), annotation.string("event"), annotation.boolean("variadic"))
            }
            val problems = mutableListOf<Pair<PsiElement, String>>()
            for (parameter in parameters) {
                if (parameter.symbol.annotations.any { it.classId?.asFqNameString() == REQUIRED } &&
                    parameter.name.asString() !in suppliedNames && suppliedBindings.none { it.prop == parameter.name.asString() }) {
                    problems += element.calleeExpression!! to "KVUE: Required prop '${parameter.name}' needs a value or a binding."
                }
            }
            for ((argument, prop, event, variadic) in suppliedBindings) {
                if (suppliedBindings.count { it.prop == prop } > 1) {
                    problems += argument to "KVUE: Use only one binding alias for '$prop'."
                }
                if (prop in suppliedNames || event in suppliedNames) {
                    problems += argument to "KVUE: A binding cannot be combined with '$prop' or '$event'."
                }
                val target = KtPsiUtil.deparenthesize(argument)
                if (target !is KtNameReferenceExpression &&
                    !(target is KtDotQualifiedExpression && target.selectorExpression is KtNameReferenceExpression)) {
                    problems += argument to "KVUE: A binding needs a writable variable or property."
                    continue
                }
                val access = target.resolveToCall()?.singleVariableAccessCall() ?: continue
                val variable = access.signature.symbol
                if (variable.isVal) {
                    problems += argument to "KVUE: '${variable.name}' is read-only; a binding needs a setter."
                    continue
                }
                if (variable is KaPropertySymbol) {
                    val setter = variable.setter
                    val receiver = (target as? KtDotQualifiedExpression)?.receiverExpression
                    val visibility = createUseSiteVisibilityChecker(element.containingKtFile.symbol, receiver, element)
                    if (setter == null || !visibility.isVisible(setter)) {
                        problems += argument to "KVUE: Setter for '${variable.name}' is not accessible here."
                        continue
                    }
                }
                val callback = parameters.find { it.name.asString() == event }?.returnType as? KaFunctionType ?: continue
                val eventType = callback.parameterTypes.firstOrNull() ?: continue
                val targetType = access.signature.returnType
                if (!variadic && (eventType as? KaClassType)?.classId?.asFqNameString() != "kotlin.Any" && !eventType.isSubtypeOf(targetType)) {
                    problems += argument to "KVUE: The update event value cannot be assigned to '${variable.name}'."
                }
            }
            problems
        }
        issues.forEach { (location, message) -> holder.newAnnotation(HighlightSeverity.ERROR, message).range(location).create() }
    }
}

private const val BIND = "kvue.annotation.VBind"
private const val REQUIRED = "kvue.annotation.RequiredBindingProp"
private fun KaAnnotation.string(name: String): String =
    ((arguments.find { it.name.asString() == name }?.expression as? KaAnnotationValue.ConstantValue)?.value?.value as? String).orEmpty()
private fun KaAnnotation.boolean(name: String): Boolean =
    ((arguments.find { it.name.asString() == name }?.expression as? KaAnnotationValue.ConstantValue)?.value?.value as? Boolean) ?: false
