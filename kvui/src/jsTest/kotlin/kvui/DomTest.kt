package kvui

import kvui.fixtures.DomChoiceHostVue
import kvui.fixtures.DomModifiersHostVue
import kvui.fixtures.DomStructureHostVue
import kvui.fixtures.DomTextHostVue
import kvue.VueComponentType
import kvue.provided
import kvue.vdom.RenderFun
import kvue.vdom.buildVNodes
import kotlin.js.Promise
import kotlin.test.*
import vue.createApp
import vue.nextTick

private external val document: dynamic

class DomTest {
    @Test
    fun nativeElementsKeepAttributesTextEventsAndKeyedIdentity(): Promise<Unit> =
        withDomMounted(DomStructureHostVue.definition) { host ->
            val attrs = host.querySelector("#attributes")
            val firstItem = host.querySelector("[data-key='a']")
            assertEquals("<b>plain text</b>", host.querySelector("h1").textContent as String)
            assertNull(host.querySelector("h1 b"))
            assertEquals("page", host.querySelector("main").className as String)
            assertEquals("rgb(10, 20, 30)", host.querySelector("main").style.color as String)
            assertEquals("before", attrs.className as String)
            assertEquals("accessible", attrs.getAttribute("aria-label") as String)
            assertEquals("custom", attrs.getAttribute("data-probe") as String)
            assertEquals("plain-input", host.querySelector("label").htmlFor as String)
            assertEquals("description", host.querySelector("img").alt as String)
            assertEquals("24", host.querySelector("img").getAttribute("width") as String)
            assertEquals(0, host.querySelector("input").childNodes.length as Int)
            assertEquals("yes", host.querySelector("kvui-example").getAttribute("data-custom") as String)
            host.querySelector("#change-dom").click()
            nextTick().then {
                assertEquals("after", attrs.className as String)
                assertTrue(attrs.hidden as Boolean)
                assertNull(attrs.getAttribute("title"))
                assertTrue(host.querySelector("#plain-input").disabled as Boolean)
                assertEquals("click", host.querySelector("#dom-event").textContent as String)
                assertEquals("b", host.querySelector("li").textContent as String)
                assertTrue(firstItem === host.querySelector("li:last-child"))
                val event = js("new Event('submit', { bubbles: true, cancelable: true })")
                host.querySelector("form").dispatchEvent(event)
                assertTrue(event.defaultPrevented as Boolean)
                nextTick()
            }.then {
                assertEquals("submit", host.querySelector("#dom-event").textContent as String)
                host.querySelector("#change-dom").click()
                nextTick()
            }.then {
                assertFalse(attrs.hidden as Boolean)
                assertFalse(host.querySelector("#plain-input").disabled as Boolean)
            }
        }

    @Test
    fun textModelsHandleNullImeNativeEventsAndParentUpdates(): Promise<Unit> =
        withDomMounted(DomTextHostVue.definition) { host ->
            val input = host.querySelector("input")
            val textarea = host.querySelector("textarea")
            fun state(): String = host.querySelector("#text-model").textContent as String
            assertEquals("", input.value as String)
            assertEquals("", textarea.value as String)
            assertFalse(input.hasAttribute("modelValue") as Boolean)
            input.dispatchEvent(js("new Event('compositionstart')"))
            input.value = "中"
            input.dispatchEvent(js("new Event('input', { bubbles: true })"))
            nextTick().then {
                assertEquals("<null>", state())
                assertEquals("1", host.querySelector("#input-events").textContent as String)
                input.value = "中文"
                input.dispatchEvent(js("new Event('compositionend')"))
                nextTick()
            }.then {
                assertEquals("中文", state())
                assertEquals("中文", textarea.value as String)
                textarea.value = "line one\nline two"
                textarea.dispatchEvent(js("new Event('input', { bubbles: true })"))
                nextTick()
            }.then {
                assertEquals("line one\nline two", state())
                host.querySelector("#replace-text").click()
                nextTick()
            }.then {
                assertEquals("parent", input.value as String)
                assertEquals("parent", textarea.value as String)
                host.querySelector("#clear-text").click()
                nextTick()
            }.then {
                assertEquals("", input.value as String)
                assertEquals("", textarea.value as String)
                assertEquals("<null>", state())
            }
        }

    @Test
    fun checkboxRadioAndSelectUseTheirNativeModelSemantics(): Promise<Unit> =
        withDomMounted(DomChoiceHostVue.definition) { host ->
            fun find(id: String): dynamic = host.querySelector("#$id")
            assertFalse(find("bound-checkbox").checked as Boolean)
            assertTrue(find("radio-a").checked as Boolean)
            assertTrue(find("check-a").checked as Boolean)
            assertEquals("first", find("single-select").value as String)
            assertTrue(find("multi-select").options[0].selected as Boolean)
            find("bound-checkbox").click()
            find("radio-b").click()
            find("check-b").click()
            find("single-select").value = "second"
            find("single-select").dispatchEvent(js("new Event('change', { bubbles: true })"))
            find("multi-select").options[1].selected = true
            find("multi-select").dispatchEvent(js("new Event('change', { bubbles: true })"))
            nextTick().then {
                assertEquals("true|b|second|first, second|a, b", find("choice-model").textContent as String)
                assertFalse(find("radio-a").checked as Boolean)
                find("check-a").click()
                nextTick()
            }.then {
                assertEquals("true|b|second|first, second|b", find("choice-model").textContent as String)
                find("reset-choices").click()
                nextTick()
            }.then {
                assertFalse(find("bound-checkbox").checked as Boolean)
                assertTrue(find("radio-a").checked as Boolean)
                assertFalse(find("radio-b").checked as Boolean)
                assertTrue(find("check-a").checked as Boolean)
                assertFalse(find("check-b").checked as Boolean)
                assertEquals("first", find("single-select").value as String)
                assertTrue(find("multi-select").options[0].selected as Boolean)
                assertFalse(find("multi-select").options[1].selected as Boolean)
            }
        }

    @Test
    fun modelModifiersAndExplicitUpdateCallbacksSurviveRenders(): Promise<Unit> =
        withDomMounted(DomModifiersHostVue.definition) { host ->
            fun state(): String = host.querySelector("#modifier-model").textContent as String
            val lazy = host.querySelector("#lazy-text")
            val numeric = host.querySelector("#numeric-text")
            val direct = host.querySelector("#direct-text")
            lazy.value = "  trimmed  "
            lazy.dispatchEvent(js("new Event('input')"))
            nextTick().then {
                assertEquals("seed|2|true|direct", state())
                lazy.dispatchEvent(js("new Event('change')"))
                numeric.value = "12.5"
                numeric.dispatchEvent(js("new Event('input')"))
                direct.value = "typed"
                direct.dispatchEvent(js("new Event('input')"))
                nextTick()
            }.then {
                assertEquals("trimmed|12.5|true|old:typed", state())
                assertEquals("trimmed", lazy.value as String)
                host.querySelector("#replace-handler").click()
                nextTick()
            }.then {
                direct.value = "again"
                direct.dispatchEvent(js("new Event('input')"))
                numeric.value = ""
                numeric.dispatchEvent(js("new Event('input')"))
                nextTick()
            }.then {
                assertEquals("trimmed||false|new:again", state())
            }
        }

    @Test
    fun invalidNativeContentAndFileModelsAreRejected() {
        assertFailsWith<IllegalArgumentException> { RenderFun { element("img") { text("invalid") } }.buildVNodes() }
        assertFailsWith<IllegalArgumentException> { RenderFun { input(type = "file", modelValue = provided("")) }.buildVNodes() }
        assertFailsWith<IllegalArgumentException> { RenderFun { input(onUpdateModelValue = {}) }.buildVNodes() }
    }
}

private fun withDomMounted(definition: VueComponentType, assertions: (dynamic) -> Promise<Unit>): Promise<Unit> {
    val host = document.createElement("div")
    document.body.appendChild(host)
    val app = createApp(definition.vue)
    fun cleanup() { app.unmount(); host.remove() }
    val result = try { app.mount(host); assertions(host) } catch (error: Throwable) { cleanup(); throw error }
    return result.then(onFulfilled = { cleanup() }, onRejected = { error -> cleanup(); throw error })
}
