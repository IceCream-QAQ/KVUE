@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class, org.jetbrains.kotlin.analysis.api.KaSpiExtensionPoint::class)

package kvue.idea

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import kvue.codegen.ComponentCodegen
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.resolve.extensions.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import com.intellij.psi.util.PsiTreeUtil

class KvueResolveExtensionProvider : KaResolveExtensionProvider() {
    override fun provideExtensionsFor(module: KaModule): List<KaResolveExtension> =
        module.project.service<KvueProjectService>().snapshot(module)?.let { listOf(KvueResolveExtension(module.project, it)) }.orEmpty()
}

internal data class ModuleSnapshot(
    val files: List<KaResolveExtensionFile>,
    val shadowedPaths: Set<String>,
    val componentNames: Set<String>,
    val fingerprint: String,
)

private class KvueResolveExtension(private val project: Project, private val snapshot: ModuleSnapshot) : KaResolveExtension() {
    override fun getKtFiles() = snapshot.files
    override fun getContainedPackages(): Set<FqName> = snapshot.files.map { it.getFilePackageName() }.toSet()
    override fun getShadowedScope(): GlobalSearchScope = object : GlobalSearchScope(project) {
        override fun contains(file: VirtualFile) = file.path in snapshot.shadowedPaths
        override fun isSearchInModuleContent(aModule: com.intellij.openapi.module.Module) = true
        override fun isSearchInLibraries() = false
    }
}

internal class ComponentVirtualFile(private val component: EditorComponent) : KaResolveExtensionFile() {
    private val model = component.model
    private val text by lazy { ComponentCodegen.generate(model, declarationsOnly = true).single().toString() }
    override fun getFileName() = model.fileName + ".kt"
    override fun getFilePackageName() = FqName(model.className.packageName)
    override fun getTopLevelClassifierNames() = model.generatedClassNames.map { Name.identifier(it.simpleName) }.toSet()
    override fun getTopLevelCallableNames() = setOf(Name.identifier(model.className.simpleName))
    override fun buildFileText() = text
    override fun createNavigationTargetsProvider() = object : KaResolveExtensionNavigationTargetsProvider() {
        override fun KaSession.getNavigationTargets(element: KtElement): Collection<PsiElement> {
            val declaration = PsiTreeUtil.getParentOfType(element, KtNamedDeclaration::class.java, false)
            val owner = PsiTreeUtil.getParentOfType(element, org.jetbrains.kotlin.psi.KtClass::class.java)
            val isSlotApi = owner?.name in setOf(model.contentName.simpleName, model.slotsName.simpleName) ||
                (owner?.name == model.nodeName.simpleName && declaration?.name == "slot")
            val target = if (isSlotApi) component.origin.element
                else component.members[declaration?.name]?.element ?: component.origin.element
            return listOfNotNull(target)
        }
    }
}
