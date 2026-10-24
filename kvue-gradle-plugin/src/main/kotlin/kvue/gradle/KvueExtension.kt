package kvue.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class KvueExtension @Inject constructor(objects: ObjectFactory) {
    val version: Property<String> = objects.property(String::class.java).convention("0.1.0-SNAPSHOT")
    val targetName: Property<String> = objects.property(String::class.java).convention("js")
    val processTests: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val addRuntimeDependency: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val addProcessorDependency: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val runtimeProject: Property<String> = objects.property(String::class.java)
    val processorProject: Property<String> = objects.property(String::class.java)
    val componentsFile: RegularFileProperty = objects.fileProperty()
    val testComponentsFile: RegularFileProperty = objects.fileProperty()
    val typescriptVersion: Property<String> = objects.property(String::class.java).convention("6.0.3")
}
