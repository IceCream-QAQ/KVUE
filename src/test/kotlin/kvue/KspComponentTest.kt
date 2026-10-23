package kvue

import fixtures.CounterHostVue
import fixtures.DefaultsHostVue
import fixtures.EmptyComponent
import fixtures.FailingComponentVue
import fixtures.SlotHostVue
import fixtures.SlotProbe
import kvue.`fun`.jso
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vue.createApp
import vue.nextTick

private external val document: dynamic

class KspComponentTest {
    @Test
    fun generatedDslPreservesInstanceStatePropsEventsAndSlots(): Promise<Unit> =
        withMounted(CounterHostVue.definition) { host ->
            fun text(selector: String): String = host.querySelector(selector).textContent as String
            fun click(selector: String) { host.querySelector(selector).click() }

            assertEquals("left:2:1", text("[data-counter='left']"))
            assertEquals("right:10:1", text("[data-counter='right']"))
            assertEquals("first", text("[data-slot='left']"))
            assertEquals("header", text("[data-header='left']"))
            click("[data-counter='left']")
            nextTick().then {
                assertEquals("left:3:1", text("[data-counter='left']"))
                assertEquals("right:10:1", text("[data-counter='right']"))
                assertEquals("old:left:3", text("[data-event]"))
                assertEquals("3", text("[data-model]"))
                click("[data-action='step']")
                click("[data-action='handler']")
                click("[data-action='slot']")
                nextTick()
            }.then {
                assertEquals("left:3:5", text("[data-counter='left']"))
                assertEquals("second", text("[data-slot='left']"))
                click("[data-counter='left']")
                click("[data-counter='right']")
                nextTick()
            }.then {
                assertEquals("new:left:8", text("[data-event]"))
                assertEquals("8", text("[data-model]"))
                assertEquals("right:11:1", text("[data-counter='right']"))
                click("[data-action='toggle']")
                nextTick()
            }.then {
                assertNull(host.querySelector("[data-counter='left']"))
                assertEquals("right:11:1", text("[data-counter='right']"))
                click("[data-action='toggle']")
                nextTick()
            }.then {
                assertEquals("left:2:5", text("[data-counter='left']"))
                assertEquals("right:11:1", text("[data-counter='right']"))
                click("[data-reset='left']")
                nextTick()
            }.then {
                assertEquals("1", text("[data-resets]"))
            }
        }

    @Test
    fun optionalPropsDistinguishOmissionNullAndFalsyValues(): Promise<Unit> =
        withMounted(DefaultsHostVue.definition) { host ->
            fun text(selector: String): String = host.querySelector(selector).textContent as String

            assertEquals("fallback|null", text("[data-nullable='omitted']"))
            assertEquals("null|null", text("[data-nullable='null']"))
            assertEquals("custom|present", text("[data-nullable='value']"))
            assertEquals("default:7:1", text("[data-counter='default']"))
            assertEquals("zero:0:0", text("[data-counter='zero']"))
            assertEquals("empty", text("[data-empty]"))
            assertEquals("inherited", text("[data-inherited]"))
            host.querySelector("[data-counter='zero']").click()
            nextTick().then { assertEquals("zero:0:0", text("[data-counter='zero']")) }
        }

    @Test
    fun slotsAreNotEvaluatedUntilTheChildInvokesThem(): Promise<Unit> {
        SlotProbe.calls = 0
        return withMounted(SlotHostVue.definition) { host ->
            assertEquals(0, SlotProbe.calls)
            host.querySelector("[data-action='unrelated']").click()
            nextTick().then {
                assertEquals(0, SlotProbe.calls)
                host.querySelector("[data-action='show']").click()
                nextTick()
            }.then {
                assertTrue(SlotProbe.calls > 0)
                assertEquals("lazy", host.querySelector("[data-lazy]").textContent as String)
            }
        }
    }

    @Test
    fun failedConstructionDoesNotLeakTheInstanceContext() {
        val error = assertFailsWith<IllegalStateException> {
            FailingComponentVue.definition.vue.setup(jso(), jso())
        }
        assertEquals("Expected constructor failure", error.message)
        val directConstruction = assertFailsWith<IllegalStateException> { EmptyComponent() }
        assertTrue(directConstruction.message.orEmpty().contains("generated RenderGroup"))
    }
}

private fun withMounted(
    definition: ComponentDefinition<*>,
    assertions: (dynamic) -> Promise<Unit>,
): Promise<Unit> {
    val host = document.createElement("div")
    document.body.appendChild(host)
    val app = createApp(definition.vue)
    fun cleanup() {
        app.unmount()
        host.remove()
    }
    val result = try {
        app.mount(host)
        assertions(host)
    } catch (error: Throwable) {
        cleanup()
        throw error
    }
    return result.then(
        onFulfilled = { cleanup() },
        onRejected = { error -> cleanup(); throw error },
    )
}
