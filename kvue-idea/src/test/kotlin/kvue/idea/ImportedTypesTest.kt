package kvue.idea

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportedTypesTest {
    @Test fun preservesNullableFunctionsReturnsAndNestedArrays() {
        assertEquals("kotlin.String?", importedType("String?").toString())
        assertEquals("kotlin.Array<kotlin.Any?>", importedType("Array<Any?>").toString())
        assertEquals("((kotlin.String) -> kotlin.String?)?", importedType("((String) -> String?)?").toString())
        assertEquals("(kotlin.Double, kotlin.String) -> kotlin.Unit", importedType("(Double, String) -> Unit").toString())
        assertEquals("kotlin.Array<(kotlin.Array<kotlin.String>) -> kotlin.String?>", importedType("Array<(Array<String>) -> String?>").toString())
    }
}
