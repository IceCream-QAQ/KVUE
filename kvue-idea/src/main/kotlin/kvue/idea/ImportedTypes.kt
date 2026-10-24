package kvue.idea

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName

/** Parses the deliberately small Kotlin type vocabulary emitted by the JS importer. */
internal fun importedType(text: String): TypeName {
    val type = text.trim()
    require(type.isNotEmpty()) { "Empty importer type" }
    val arrow = topLevelPositions(type).firstOrNull { index -> type.startsWith("->", index) }
    if (arrow != null) {
        val arguments = type.substring(0, arrow).trim()
        require(arguments.startsWith('(') && arguments.endsWith(')')) { "Invalid callback type: $type" }
        val params = splitTypes(arguments.substring(1, arguments.length - 1)).map { ParameterSpec.unnamed(importedType(it)) }
        return LambdaTypeName.get(parameters = params, returnType = importedType(type.substring(arrow + 2)))
    }
    if (type.endsWith('?')) return importedType(type.dropLast(1)).copy(nullable = true)
    if (type.startsWith('(') && type.endsWith(')')) return importedType(type.substring(1, type.length - 1))
    val start = type.indexOf('<')
    val name = if (start < 0) type else type.substring(0, start)
    require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*"))) { "Unsupported importer type: $type" }
    val className = if ('.' in name) ClassName.bestGuess(name) else ClassName("kotlin", name)
    if (start < 0) return className
    require(type.endsWith('>')) { "Invalid generic type: $type" }
    return className.parameterizedBy(splitTypes(type.substring(start + 1, type.length - 1)).map(::importedType))
}

private fun splitTypes(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val commas = topLevelPositions(text).filter { text[it] == ',' }
    val boundaries = listOf(-1) + commas + listOf(text.length)
    return boundaries.zipWithNext { start, end -> text.substring(start + 1, end).trim() }
}

private fun topLevelPositions(text: String): List<Int> {
    var parentheses = 0
    var generics = 0
    val result = mutableListOf<Int>()
    text.forEachIndexed { index, character ->
        if (parentheses == 0 && generics == 0) result += index
        when (character) {
            '(' -> parentheses++
            ')' -> parentheses--
            '<' -> generics++
            '>' -> if (index == 0 || text[index - 1] != '-') generics--
        }
        require(parentheses >= 0 && generics >= 0) { "Unbalanced importer type: $text" }
    }
    require(parentheses == 0 && generics == 0) { "Unbalanced importer type: $text" }
    return result
}
