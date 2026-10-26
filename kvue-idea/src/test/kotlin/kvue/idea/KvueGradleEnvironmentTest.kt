package kvue.idea

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListenerAdapter
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.testFramework.TestApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.gradle.tooling.BuildLauncher
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.LongRunningOperation
import org.gradle.tooling.ModelBuilder
import org.gradle.tooling.model.BuildIdentifier
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.build.GradleEnvironment
import org.gradle.tooling.model.build.JavaEnvironment
import org.jetbrains.plugins.gradle.service.execution.GradleExecutionContextImpl
import org.jetbrains.plugins.gradle.service.execution.GradleExecutionHelper
import org.jetbrains.plugins.gradle.service.project.GradleExecutionHelperExtension
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import java.lang.reflect.Proxy

class KvueGradleEnvironmentTest : BasePlatformTestCase() {
    // Environment services can retain application-lifetime temp resources. Do not redirect
    // FileUtil's global temp path to a single test; the platform fixture still cleans its project.
    override fun shouldContainTempFiles() = false

    override fun setUp() {
        TestApplicationManager.getInstance()
        // Same IU 262 flat-test-classpath workaround as KvueResolveTest; never shipped in the plugin.
        ExtensionPointName.create<Any>("com.intellij.postStartupActivity").point.unregisterExtensions(
            { name, adapter -> !(name == "Z.Z.Z.Z.Z" && adapter.pluginDescriptor.pluginId.idString == "com.intellij.modules.ultimate") }, false,
        )
        super.setUp()
    }

    fun testModelImportAndBuildExecutionReceiveDefaultWithoutLosingUserEnvironment() {
        assertTrue(GradleExecutionHelperExtension.EP_NAME.extensionList.any { it is KvueGradleEnvironment })
        for (type in listOf(ExternalSystemTaskType.RESOLVE_PROJECT, ExternalSystemTaskType.EXECUTE_TASK)) {
            val settings = GradleExecutionSettings().apply {
                passParentEnvs(false)
                withEnvironmentVariables(mapOf("PATH" to "custom-path", "JAVA_HOME" to "custom-java", "KVUE_EXPOSE_GENERATED_SOURCES" to "true"))
            }
            val environment = prepareOperation(type, settings)
            assertEquals("false", environment[KvueGradleEnvironment.DEFAULT_VARIABLE])
            assertEquals("true", environment["KVUE_EXPOSE_GENERATED_SOURCES"])
            assertEquals("custom-path", environment["PATH"])
            assertEquals("custom-java", environment["JAVA_HOME"])
            assertFalse(settings.env.containsKey(KvueGradleEnvironment.DEFAULT_VARIABLE)) // only the operation's copy changes
            assertFalse(settings.isPassParentEnvs)
        }
    }

    fun testExplicitDefaultAndParentEnvironmentArePreserved() {
        val settings = GradleExecutionSettings().apply {
            passParentEnvs(true)
            addEnvironmentVariable(KvueGradleEnvironment.DEFAULT_VARIABLE, "true")
            addEnvironmentVariable("KVUE_TEST_SENTINEL", "retained")
        }
        val environment = prepareOperation(ExternalSystemTaskType.RESOLVE_PROJECT, settings)
        assertEquals("true", environment[KvueGradleEnvironment.DEFAULT_VARIABLE])
        assertEquals("retained", environment["KVUE_TEST_SENTINEL"])
        assertTrue(environment.keys.any { it.equals("PATH", ignoreCase = true) })
    }

    private fun prepareOperation(type: ExternalSystemTaskType, settings: GradleExecutionSettings): Map<String, String> {
        var environment: Map<String, String>? = null
        val operationClass = if (type == ExternalSystemTaskType.RESOLVE_PROJECT) ModelBuilder::class.java else BuildLauncher::class.java
        val operation = Proxy.newProxyInstance(operationClass.classLoader, arrayOf(operationClass)) { proxy, method, args ->
            if (method.name == "setEnvironmentVariables") {
                @Suppress("UNCHECKED_CAST")
                environment = (args!![0] as Map<String, String>).toMap()
            }
            if (method.returnType.isInstance(proxy)) proxy else null
        } as LongRunningOperation
        val context = GradleExecutionContextImpl(
            project.basePath!!, ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID, type, project), settings,
            object : ExternalSystemTaskNotificationListenerAdapter() {}, GradleConnector.newCancellationTokenSource().token(),
        )
        context.buildEnvironment = object : BuildEnvironment {
            override fun getBuildIdentifier() = object : BuildIdentifier { override fun getRootDir() = File(project.basePath!!) }
            override fun getGradle() = object : GradleEnvironment {
                override fun getGradleUserHome() = File(project.basePath!!, ".gradle")
                override fun getGradleVersion() = "9.7.1"
            }
            override fun getJava() = object : JavaEnvironment {
                override fun getJavaHome() = File(System.getProperty("java.home"))
                override fun getJvmArguments() = emptyList<String>()
            }
            override fun getVersionInfo() = "9.7.1"
        }
        GradleExecutionHelper.prepareForExecution(operation, context)
        return requireNotNull(environment)
    }
}
