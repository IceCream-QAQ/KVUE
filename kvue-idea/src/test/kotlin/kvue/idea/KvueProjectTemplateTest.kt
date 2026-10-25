package kvue.idea

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.Properties
import java.util.zip.ZipFile

class KvueProjectTemplateTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun basicStarterContainsUsableWrapperAndNoOptionalLibrary() {
        val root = temporary.newFolder("basic").toPath()
        val entry = KvueProjectTemplate.generate(root, KvueProjectOptions("hello", packageName = "demo.hello"))
        assertEquals(root.resolve("src/jsMain/kotlin/demo/hello/App.kt"), entry)
        assertTrue(Files.readString(entry).contains("input(vModel = name"))
        assertFalse(Files.exists(root.resolve("kvue.components.json")))
        assertFalse(Files.readString(root.resolve("build.gradle.kts")).contains("element-plus"))
        assertTrue(Files.readString(root.resolve("src/jsMain/kotlin/demo/hello/Main.kt")).contains("createApp(AppVue.definition)"))
        val settings = Files.readString(root.resolve("settings.gradle.kts"))
        assertEquals(2, Regex("https://maven.pkg.github.com/icecream-qaq/kvue").findAll(settings).count())
        assertTrue(settings.contains("kvue.github.token"))
        assertFalse(Files.readString(root.resolve("gradle.properties")).contains("token"))
        assertFalse(Files.readString(root.resolve("gradlew")).contains('\r'))
        assertTrue(Files.size(root.resolve("gradlew.bat")) > 0)
        ZipFile(root.resolve("gradle/wrapper/gradle-wrapper.jar").toFile()).use {
            assertNotNull(it.getEntry("org/gradle/wrapper/GradleWrapperMain.class"))
        }
        val properties = Properties().apply {
            Files.newInputStream(root.resolve("gradle/wrapper/gradle-wrapper.properties")).use(::load)
        }
        assertTrue(properties.getProperty("distributionUrl").endsWith("gradle-9.7.1-bin.zip"))
        assertEquals(64, properties.getProperty("distributionSha256Sum").length)
    }

    @Test fun elementPlusStarterKeepsVersionsImportsAndSelectedExportsInSync() {
        val root = temporary.newFolder("element-plus").toPath()
        val repository = temporary.newFolder("local repo").toPath()
        val entry = KvueProjectTemplate.generate(root, KvueProjectOptions(
            "widgets", "com.example.widgets", "0.2.0-SNAPSHOT", KvueStarter.ELEMENT_PLUS,
            KvueRepository.LOCAL, repository.toString(),
        ))
        val build = Files.readString(root.resolve("build.gradle.kts"))
        assertTrue(build.contains("id(\"org.kvue\") version \"0.2.0-SNAPSHOT\""))
        assertTrue(build.contains("org.kvue:kvui:0.2.0-SNAPSHOT"))
        assertTrue(build.contains("version.set(\"0.2.0-SNAPSHOT\")"))
        assertTrue(build.contains("npm(\"element-plus\", \"2.14.7\")"))
        val settings = Files.readString(root.resolve("settings.gradle.kts"))
        assertTrue(settings.contains(repository.toUri().toASCIIString()))
        assertFalse(settings.contains("credentials"))
        val config = JsonParser.parseString(Files.readString(root.resolve("kvue.components.json"))).asJsonObject
            .getAsJsonArray("libraries").single().asJsonObject
        assertEquals("com.example.widgets.elementplus", config["package"].asString)
        assertEquals(listOf("ElButton", "ElCard", "ElInput"), config["exports"].asJsonArray.map { it.asString })
        assertTrue(Files.readString(entry).contains("import com.example.widgets.elementplus.ElInput"))
        assertTrue(Files.readString(entry).contains(".onClick { count++ }"))
        assertTrue(Files.readString(entry.resolveSibling("Main.kt")).contains("element-plus/dist/index.css"))
    }

    @Test fun existingFilesArePreservedBeforeAnyFilesAreWritten() {
        val root = temporary.newFolder("existing").toPath()
        Files.writeString(root.resolve("build.gradle.kts"), "// existing project")
        assertThrows(IllegalArgumentException::class.java) {
            KvueProjectTemplate.generate(root, KvueProjectOptions("existing"))
        }
        assertEquals("// existing project", Files.readString(root.resolve("build.gradle.kts")))
        assertFalse(Files.exists(root.resolve("settings.gradle.kts")))
    }

    @Test fun invalidInputsFailWithoutCreatingAnIncompleteProject() {
        for (options in listOf(
            KvueProjectOptions("app", packageName = "foo.class"),
            KvueProjectOptions("app", packageName = "../foo"),
            KvueProjectOptions("app", version = "1.0\"; error()"),
            KvueProjectOptions("app", repository = KvueRepository.LOCAL, localRepository = ""),
        )) {
            val root = temporary.root.toPath().resolve("not-created")
            assertThrows(IllegalArgumentException::class.java) { KvueProjectTemplate.generate(root, options) }
            assertFalse(Files.exists(root))
        }
    }

    @Test fun projectNamesAreEscapedWithoutInterpretingTemplateMarkers() {
        val root = temporary.newFolder("escaping").toPath()
        KvueProjectTemplate.generate(root, KvueProjectOptions("hello \"${'$'}name\" <&> {{PACKAGE}}"))
        val settings = Files.readString(root.resolve("settings.gradle.kts"))
        assertTrue(settings.contains("\\${'$'}name"))
        assertTrue(settings.contains("\\\""))
        assertTrue(settings.contains("{{PACKAGE}}"))
        val html = Files.readString(root.resolve("src/jsMain/resources/index.html"))
        assertTrue(html.contains("&lt;&amp;&gt;"))
        assertTrue(html.contains("{{PACKAGE}}"))
    }
}
