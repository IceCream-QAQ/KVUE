@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class, org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class,
    org.jetbrains.kotlin.analysis.api.KaSpiExtensionPoint::class)

package kvue.idea

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

/** Ctrl+click / Go to Declaration opens the original source directly. */
class KvueGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val expression = PsiTreeUtil.getParentOfType(sourceElement, KtSimpleNameExpression::class.java, false) ?: return null
        val project = expression.project
        if (DumbService.isDumb(project)) return null
        val reference = expression.mainReference
        val resolved = reference.multiResolve(false).mapNotNull { it.element }.filterIsInstance<KtElement>()
            .filter { it.containingKtFile.name.endsWith("KVueGenerated.kt") || it.containingKtFile.name.endsWith("JsSchema.kt") }
        if (resolved.isEmpty()) return null
        val service = project.service<KvueProjectService>()
        val targets = analyze(expression) {
            resolved.flatMap { element ->
                if (!element.isFromResolveExtension) return@flatMap emptyList()
                val file = element.containingKtFile
                val module = KaModuleProvider.getModule(project, file, null)
                val owned = service.snapshot(module)?.files?.any {
                    it.getFileName() == file.name && it.getFilePackageName() == file.packageFqName
                } == true
                if (owned) element.resolveExtensionNavigationElements else emptyList()
            }
        }.filter { it.isValid }.distinct()
        return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}
