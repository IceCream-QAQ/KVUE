package kvue

import kvue.fixtures.*
import kotlin.js.Promise
import kotlin.test.*
import vue.nextTick

private external val document: dynamic

class ChainedComponentTest {
    @Test fun eventsComposeAndRefsTrackInstanceLifetime(): Promise<Unit> {
        ChainProbe.log.clear()
        ChainProbe.visible = ref(true)
        ChainProbe.model = ref(0)
        ChainProbe.revision = ref(0)
        val host = document.createElement("div")
        document.body.appendChild(host)
        val app = createApp(ChainHostVue.definition)
        fun cleanup() { app.unmount(); host.remove() }
        val result = try {
            app.mount(host)
            val first = assertNotNull(ChainProbe.child.value)
            val second = assertNotNull(ChainProbe.sibling.value)
            assertNotSame(first, second)
            assertEquals("undefined", jsTypeOf(first.asDynamic().hidden))
            val button = host.querySelector("[data-chain]")
            assertTrue(button.classList.contains("base") as Boolean)
            assertTrue(button.classList.contains("extra") as Boolean)
            assertEquals("red", button.style.color as String)
            button.click()
            nextTick().then {
                assertEquals(listOf("model:1", "argument:1:0", "chain1:1", "chain2:1"), ChainProbe.log)
                assertEquals(1, ChainProbe.model.value)
                assertEquals(1, first.count)
                assertSame(first, ChainProbe.child.value)
                assertEquals(7, first.reset(7))
                second.count = 3
                ChainProbe.revision.value++
                nextTick()
            }.then {
                assertEquals("7", host.querySelectorAll("[data-chain]")[0].textContent as String)
                assertEquals("3", host.querySelectorAll("[data-chain]")[1].textContent as String)
                ChainProbe.log.clear()
                button.click()
                nextTick()
            }.then {
                assertEquals(listOf("model:2", "argument:8:1", "chain1:8", "chain2:8"), ChainProbe.log)
                ChainProbe.visible.value = false
                nextTick()
            }.then {
                assertNull(ChainProbe.child.value)
                assertSame(second, ChainProbe.sibling.value)
                ChainProbe.visible.value = true
                nextTick()
            }.then {
                assertNotSame(first, assertNotNull(ChainProbe.child.value))
                app.unmount()
                assertNull(ChainProbe.child.value)
                assertNull(ChainProbe.sibling.value)
            }
        } catch (error: Throwable) { cleanup(); throw error }
        return result.then(onFulfilled = { host.remove() }, onRejected = { cleanup(); throw it })
    }
}
