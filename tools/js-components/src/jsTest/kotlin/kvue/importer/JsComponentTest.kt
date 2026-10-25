package kvue.importer

import kvue.importer.fixtures.ElementPlusHostVue
import kvue.importer.fixtures.ForeignHostVue
import kvue.importer.fixtures.SourceHostVue
import kvue.importer.fixtures.ImportedRefProbe
import kvue.provided
import kvue.importer.fixtures.foreign.ForeignCounterVue
import kvue.importer.fixtures.foreign.ForeignFunctionalVue
import kvue.JsComponentDefinition
import kvue.VueComponentType
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import vue.createApp
import vue.nextTick

private external val document: dynamic

@JsModule("./foreign-fixture.cjs")
@JsNonModule
private external object RawFixture {
    val ForeignCounter: dynamic
    val ForeignFunctional: dynamic
}

class JsComponentTest {
    @Test
    fun importedPublicMethodsPreserveThisOptionalNullAndRestArguments(): Promise<Unit> =
        withJsMounted(ForeignHostVue.definition) { host ->
            val instance = assertNotNull(ImportedRefProbe.foreign.value)
            assertEquals(9.0, instance.reset(provided(9.0)))
            assertEquals("9:suffix", instance.describe("suffix"))
            assertEquals(9.0, instance.count)
            assertEquals(2.0, instance.reset())
            assertEquals(0.0, instance.reset(provided(null)))
            assertEquals(3.0, instance.arity(arrayOf("a", null, 3)))
            nextTick().then {
                assertEquals("left:0", host.querySelector("[data-foreign='left'] button").textContent as String)
            }
        }.then { assertNull(ImportedRefProbe.foreign.value) }

    @Test
    fun sourceExposeAndOptionsMethodsHaveCallablePublicFacades(): Promise<Unit> =
        withJsMounted(SourceHostVue.definition) { host ->
            assertEquals(12.0, assertNotNull(ImportedRefProbe.source.value).reset(12.0))
            assertEquals("array caption!", assertNotNull(ImportedRefProbe.options.value).labelWith("!"))
            nextTick().then {
                assertTrue((host.querySelector("[data-source-counter]").textContent as String).contains(":12:"))
            }
        }.then { assertNull(ImportedRefProbe.source.value) }
    @Test
    fun importsPreserveTheOriginalComponentIdentityAndKind() {
        assertTrue(ForeignCounterVue.definition.vue === RawFixture.ForeignCounter)
        assertTrue(ForeignFunctionalVue.definition.vue === RawFixture.ForeignFunctional)
        assertEquals("function", jsTypeOf(ForeignFunctionalVue.definition.vue))
        assertFailsWith<IllegalArgumentException> { JsComponentDefinition(null, "missing export") }
    }

    @Test
    fun externalComponentsKeepTheirOwnStateDefaultsEventsAndSlots(): Promise<Unit> =
        withJsMounted(ForeignHostVue.definition) { host ->
            fun text(selector: String): String = host.querySelector(selector).textContent as String
            assertEquals("left:2", text("[data-foreign='left'] button"))
            assertEquals("right:7", text("[data-foreign='right'] button"))
            assertEquals("null", text("[data-foreign='left'] [data-note]"))
            assertEquals("fallback", text("[data-foreign='right'] [data-note]"))
            assertEquals("a prop named content", text("[data-content]"))
            assertEquals("default slot", text("[data-foreign-slot]"))
            assertEquals("named slot", text("[data-foreign-header]"))
            assertEquals("functional", text("[data-functional]"))
            assertEquals("esm-default", text("[data-default]"))
            assertEquals("commonjs-default", text("[data-commonjs]"))
            assertEquals("options:3", text("[data-options]"))
            host.querySelector("[data-foreign='left'] button").click()
            host.querySelector("[data-options]").click()
            nextTick().then {
                assertEquals("left:3", text("[data-foreign-event]"))
                assertEquals("3", text("[data-foreign-model]"))
                assertEquals("3:left:null", text("[data-foreign-bulk]"))
                assertEquals("format:2:left", text("[data-format]"))
                assertEquals("3", text("[data-foreign-accepted]"))
                host.querySelector("[data-action='rename']").click()
                nextTick()
            }.then {
                assertEquals("renamed:3", text("[data-foreign='renamed'] button"))
                assertEquals("right:7", text("[data-foreign='right'] button"))
                host.querySelector("[data-foreign='renamed'] button").click()
                nextTick()
            }.then {
                assertEquals("renamed:4", text("[data-foreign-event]"))
            }
        }

    @Test
    fun generatedElementPlusBindingsHandleClickDisabledAndModelUpdates(): Promise<Unit> =
        withJsMounted(ElementPlusHostVue.definition) { host ->
            val button = host.querySelector("button.el-button")
            val input = host.querySelector("input.el-input__inner")
            val instance = assertNotNull(ImportedRefProbe.input.value)
            instance.focus()
            assertTrue(document.activeElement === input)
            instance.blur()
            assertTrue(document.activeElement !== input)
            assertEquals("seed", input.value as String)
            button.click()
            input.value = "typed through Element Plus"
            input.dispatchEvent(js("new Event('input', { bubbles: true })"))
            nextTick().then {
                assertEquals("1", host.querySelector("[data-el-count]").textContent as String)
                assertEquals("typed through Element Plus", host.querySelector("[data-el-value]").textContent as String)
                host.querySelector("[data-action='disable']").click()
                nextTick()
            }.then {
                assertTrue(button.disabled as Boolean)
                button.click()
                nextTick()
            }.then {
                assertEquals("1", host.querySelector("[data-el-count]").textContent as String)
            }
        }

    @Test
    fun componentsWithoutDeclarationsKeepDefaultsAndForwardTypedAndUntypedEvents(): Promise<Unit> =
        withJsMounted(SourceHostVue.definition) { host ->
            fun text(selector: String): String = host.querySelector(selector).textContent as String
            assertEquals("source:4:false:null", text("[data-source-counter]"))
            assertEquals("array caption", text("[data-source-array]"))
            assertEquals("source-commonjs", text("[data-source-commonjs]"))
            assertEquals("JSDoc functional", text("[data-source-functional]"))
            host.querySelector("[data-source-counter]").click()
            host.querySelector("[data-source-array]").click()
            host.querySelector("[data-source-commonjs]").click()
            nextTick().then {
                assertEquals("source:5", text("[data-source-changed]"))
                assertEquals("array caption:9", text("[data-source-selected]"))
                assertEquals("2:extra", text("[data-source-model]"))
                assertEquals("12", text("[data-source-accepted]"))
                host.querySelector("[data-action='rename-source']").click()
                nextTick()
            }.then {
                assertEquals("renamed source:5:false:null", text("[data-source-counter]"))
            }
        }
}

private fun withJsMounted(definition: VueComponentType, assertions: (dynamic) -> Promise<Unit>): Promise<Unit> {
    val host = document.createElement("div")
    document.body.appendChild(host)
    val app = createApp(definition.vue)
    fun cleanup() { app.unmount(); host.remove() }
    val result = try { app.mount(host); assertions(host) } catch (error: Throwable) { cleanup(); throw error }
    return result.then(onFulfilled = { cleanup() }, onRejected = { error -> cleanup(); throw error })
}
