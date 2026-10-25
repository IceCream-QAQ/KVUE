package kvue.idea

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiFileRange
import com.intellij.psi.impl.FakePsiElement
import java.nio.file.Path

/** One-based UTF-16 line/column, not a byte offset or a CRLF-dependent absolute offset. */
internal data class ImportedSourceLocation(val file: String, val line: Int, val column: Int, val length: Int, val text: String?)

/** Created in the snapshot read action. Resolve callbacks only dereference these smart pointers. */
internal class ExternalNavigation(project: Project, component: ImportedComponent, configuration: Path) {
    private val pointers = SmartPointerManager.getInstance(project)
    private val files = listOfNotNull(component.navigation?.file, component.source, component.moduleSource, configuration.toString())
        .map { it.replace('\\', '/') }.distinct()
        .mapNotNull { path -> LocalFileSystem.getInstance().findFileByPath(path)?.let { PsiManager.getInstance(project).findFile(it) } }
    private val fallback = files.map { pointers.createSmartPsiElementPointer(it) }
    private val declaration = component.navigation?.let { location ->
        val targetFile = LocalFileSystem.getInstance().findFileByPath(location.file.replace('\\', '/'))
        val file = files.firstOrNull { it.virtualFile == targetFile } ?: return@let null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return@let null
        if (location.line !in 1..document.lineCount || location.column < 1 || location.length < 1) return@let null
        val lineStart = document.getLineStartOffset(location.line - 1)
        val lineEnd = document.getLineEndOffset(location.line - 1)
        val start = lineStart.toLong() + location.column - 1
        val end = start + location.length
        if (start < lineStart || end > lineEnd) return@let null
        val range = TextRange(start.toInt(), end.toInt())
        // A file may have unsaved changes newer than the analyzer result. Never jump to an unrelated token.
        if (location.text != null && document.getText(range) != location.text) return@let null
        ExternalDeclarationTarget(project, pointers.createSmartPsiFileRangePointer(file, range), component.model.className.simpleName)
    }

    fun targets(): Collection<PsiElement> = listOfNotNull(
        declaration?.takeIf { it.isValid } ?: fallback.firstNotNullOfOrNull { it.element?.takeIf(PsiElement::isValid) },
    )
}

/** Also works when the IDE has no TypeScript PSI support: the source range remains navigable. */
private class ExternalDeclarationTarget(
    private val owner: Project,
    private val pointer: SmartPsiFileRange,
    private val componentName: String,
) : FakePsiElement() {
    override fun getParent(): PsiElement? = pointer.element
    override fun getContainingFile(): PsiFile? = pointer.element
    override fun getProject(): Project = owner
    override fun getManager(): PsiManager = PsiManager.getInstance(owner)
    override fun getName(): String = componentName
    override fun getLocationString(): String? = containingFile?.virtualFile?.path
    override fun getTextRange(): TextRange? = pointer.range?.let { TextRange(it.startOffset, it.endOffset) }
    override fun getTextOffset(): Int = pointer.range?.startOffset ?: 0
    override fun getStartOffsetInParent(): Int = textOffset
    override fun getTextLength(): Int = textRange?.length ?: 0
    override fun getText(): String? {
        val text = containingFile?.text ?: return null
        val range = textRange ?: return null
        return if (range.endOffset <= text.length) text.substring(range.startOffset, range.endOffset) else null
    }
    override fun isValid(): Boolean = !owner.isDisposed && pointer.element?.isValid == true &&
        pointer.range?.let { it.endOffset > it.startOffset } == true
    override fun canNavigate(): Boolean = isValid
    override fun canNavigateToSource(): Boolean = canNavigate()
    override fun navigate(requestFocus: Boolean) {
        val file = containingFile?.virtualFile ?: return
        if (isValid) OpenFileDescriptor(owner, file, textOffset).navigate(requestFocus)
    }
}
