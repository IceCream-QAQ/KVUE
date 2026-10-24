package kvue.gradle

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.inject.Inject

@DisableCachingByDefault(because = "The analysis report records absolute input paths; local up-to-date checks are supported.")
abstract class GenerateKvueJsComponents @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val configurationFile: RegularFileProperty
    @get:Internal abstract val nodeModules: DirectoryProperty
    @get:Input abstract val nodeExecutable: Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packageInputs: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @get:InputFiles @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val localInputs: List<File> get() {
        val configuration = configurationFile.get().asFile
        if (!configuration.isFile) return emptyList()
        val json = JsonSlurper().parse(configuration) as Map<*, *>
        val explicit = (json["libraries"] as? List<*>).orEmpty().flatMap { library ->
            val fields = library as Map<*, *>
            listOfNotNull(fields["declarations"] as? String, fields["source"] as? String)
        }.map { configuration.parentFile.resolve(it) }
        val report = outputDirectory.file("report.json").get().asFile
        val discovered = if (report.isFile) {
            val previous = JsonSlurper().parse(report) as Map<*, *>
            listOf("sources", "declarations").flatMap { (previous[it] as? List<*>).orEmpty() }.filterIsInstance<String>().map(::File)
        } else emptyList()
        return (explicit + discovered).distinct()
    }

    @TaskAction fun generate() {
        val scriptDirectory = temporaryDir.resolve("js-importer").apply { mkdirs() }
        for (name in listOf("generate.cjs", "source-analysis.cjs")) {
            val content = checkNotNull(javaClass.getResourceAsStream("/js-importer/$name")) { "Missing bundled importer: $name" }.use { it.readBytes() }
            scriptDirectory.resolve(name).writeBytes(content)
        }
        exec.exec {
            it.commandLine(nodeExecutable.get(), scriptDirectory.resolve("generate.cjs").absolutePath,
                "--config", configurationFile.get().asFile.absolutePath,
                "--node-modules", nodeModules.get().asFile.absolutePath,
                "--out", outputDirectory.get().asFile.absolutePath)
        }.assertNormalExitValue()
    }
}
