@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package kvue.idea

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiUtil

/** Mirrors the compiler's prop check, which runs on IR and therefore never reaches the editor. */
class KvuePropAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is KtProperty || element.annotationEntries.isEmpty()) return
        val delegate = KtPsiUtil.deparenthesize(element.delegateExpression) ?: return
        val call = (delegate as? KtDotQualifiedExpression)?.selectorExpression ?: delegate
        if (call !is KtCallExpression || call.valueArguments.isNotEmpty()) return
        val missingDefault = analyze(element) {
            val prop = element.symbol.annotations.find { it.classId?.asFqNameString() == PROP } ?: return@analyze false
            val required = prop.arguments.find { it.name.asString() == "required" }?.expression
            if (((required as? KaAnnotationValue.ConstantValue)?.value?.value as? Boolean) == true) return@analyze false
            val function = delegate.resolveToCall()?.singleFunctionCallOrNull()?.signature?.symbol ?: return@analyze false
            function.callableId == PROPERTY && function.valueParameters.isEmpty()
        }
        if (missingDefault) {
            holder.newAnnotation(HighlightSeverity.ERROR,
                "KVUE: Optional prop '${element.name}' has no default value. Use property(default), or mark it @Prop(required = true).",
            ).range(delegate).create()
        }
    }
}

private const val PROP = "kvue.annotation.Prop"
private val PROPERTY = CallableId(ClassId.topLevel(FqName("kvue.Component")), Name.identifier("property"))
