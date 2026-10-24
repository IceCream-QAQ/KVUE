package kvue.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.GradleException
import javax.inject.Inject

abstract class KvueExtension @Inject constructor(objects: ObjectFactory, providers: ProviderFactory) {
    /** Explicit DSL values override environment defaults; -Pkvue.exposeGeneratedSources overrides the DSL. */
    val exposeGeneratedSources: Property<Boolean> = objects.property(Boolean::class.java).convention(
        providers.environmentVariable("KVUE_EXPOSE_GENERATED_SOURCES")
            .map { parseExposure(it, "KVUE_EXPOSE_GENERATED_SOURCES") }
            .orElse(providers.environmentVariable("KVUE_EXPOSE_GENERATED_SOURCES_DEFAULT")
                .map { parseExposure(it, "KVUE_EXPOSE_GENERATED_SOURCES_DEFAULT") })
            .orElse(true)
    )
    val version: Property<String> = objects.property(String::class.java).convention("0.1.0-SNAPSHOT")
    val targetName: Property<String> = objects.property(String::class.java).convention("js")
    val processTests: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val addRuntimeDependency: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val addProcessorDependency: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val runtimeProject: Property<String> = objects.property(String::class.java)
    val processorProject: Property<String> = objects.property(String::class.java)
    val compilerProject: Property<String> = objects.property(String::class.java)
    val compilerPluginEnabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val componentsFile: RegularFileProperty = objects.fileProperty()
    val testComponentsFile: RegularFileProperty = objects.fileProperty()
    val typescriptVersion: Property<String> = objects.property(String::class.java).convention("6.0.3")
}

internal fun parseExposure(value: String, source: String): Boolean = when (value.trim().lowercase()) {
    "true" -> true
    "false" -> false
    else -> throw GradleException("$source must be true or false, but was '$value'.")
}
