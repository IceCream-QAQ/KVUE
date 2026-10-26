@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package kvue.idea

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.singleVariableAccessCall
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.*

/** Mirrors the structural limits of slot extraction instead of reporting them only during builds. */
class KvueSlotAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is KtCallExpression) return
        val problem = analyze(element) {
            val call = element.resolveToCall()?.singleFunctionCallOrNull() ?: return@analyze null
            if (call.signature.symbol.annotations.none { it.classId?.asFqNameString() == SLOT }) return@analyze null
            // Generated forwarding methods are themselves slot declarations.
            val owner = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java)
            if (owner?.symbol?.annotations?.any { it.classId?.asFqNameString() == SLOT } == true) return@analyze null
            val lambda = PsiTreeUtil.getParentOfType(element, KtLambdaExpression::class.java)
                ?: return@analyze DIRECT
            val enclosingCall = when (val argument = lambda.parent) {
                is KtLambdaArgument -> argument.parent as? KtCallExpression
                is KtValueArgument -> argument.parent.parent as? KtCallExpression
                else -> null
            } ?: return@analyze DIRECT
            val enclosing = enclosingCall.resolveToCall()?.singleFunctionCallOrNull() ?: return@analyze null
            val parameter = enclosing.valueArgumentMapping.entries.firstOrNull {
                KtPsiUtil.deparenthesize(it.key) == lambda
            }?.value
            val isContent = (parameter?.returnType as? KaClassType)?.classId?.asFqNameString() == CONTENT ||
                (enclosing.signature.returnType as? KaClassType)?.classId?.asFqNameString() == CONTENT
            if (!isContent) return@analyze DIRECT
            var statement: PsiElement = element
            while (statement.parent is KtDotQualifiedExpression &&
                (statement.parent as KtDotQualifiedExpression).selectorExpression == statement) statement = statement.parent
            val body = lambda.bodyExpression ?: return@analyze null
            if (statement !in body.statements) return@analyze DIRECT
            for (reference in PsiTreeUtil.findChildrenOfType(statement, KtNameReferenceExpression::class.java)) {
                val resolved = reference.resolveToCall()
                val declaration = resolved?.singleVariableAccessCall()?.signature?.symbol?.psi
                    ?: resolved?.singleFunctionCallOrNull()?.signature?.symbol?.psi ?: continue
                if (PsiTreeUtil.isAncestor(body, declaration, true) && !PsiTreeUtil.isAncestor(statement, declaration, false)) {
                    return@analyze SHARED
                }
            }
            null
        }
        if (problem != null) holder.newAnnotation(HighlightSeverity.ERROR, "KVUE: $problem").range(element).create()
    }
}

private const val SLOT = "kvue.annotation.SlotDeclaration"
private const val CONTENT = "kvue.vdom.ComponentContent"
private const val DIRECT = "Declare slots directly in the component content block. Use the returned node for conditional registration."
private const val SHARED = "A named slot cannot capture a declaration from implicit default content. Move shared declarations before the component call, or into the individual slot."
