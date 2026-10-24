package kvue.idea

import org.jetbrains.plugins.gradle.service.execution.GradleExecutionContext
import org.jetbrains.plugins.gradle.service.project.GradleExecutionHelperExtension
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings

/** Applied to both model import and task execution, before IDEA merges the effective environment. */
class KvueGradleEnvironment : GradleExecutionHelperExtension {
    override fun configureSettings(settings: GradleExecutionSettings, context: GradleExecutionContext) {
        if (!settings.env.containsKey(DEFAULT_VARIABLE)) {
            settings.addEnvironmentVariable(DEFAULT_VARIABLE, "false")
        }
    }

    companion object {
        const val DEFAULT_VARIABLE = "KVUE_EXPOSE_GENERATED_SOURCES_DEFAULT"
    }
}
