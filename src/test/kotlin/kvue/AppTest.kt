package kvue

import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import test.App
import vue.createApp
import vue.nextTick

private external val document: dynamic

class AppTest {
    @Test
    fun counterPropsAndInputRemainReactive(): Promise<Unit> {
        val host = document.createElement("div")
        document.body.appendChild(host)
        val app = createApp(App().buildVueComponent())

        fun cleanup() {
            app.unmount()
            host.remove()
        }

        val assertions = try {
            app.mount(host)
            assertEquals("Hello, world! 0", host.querySelector("div").textContent as String)
            assertEquals("123456", host.querySelector("input").value as String)

            repeat(6) { host.querySelector("button").click() }

            nextTick().then {
                assertEquals("Hello, world! 6", host.querySelector("div").textContent as String)
                assertEquals(7, host.querySelectorAll("div").length as Int)
                assertEquals("SubComponent: 6, 123456", host.lastElementChild.textContent as String)

                val input = host.querySelector("input")
                input.value = "updated"
                input.dispatchEvent(js("new Event('input', { bubbles: true })"))
                nextTick()
            }.then {
                assertEquals("SubComponent: 1, updated", host.querySelectorAll("div")[1].textContent as String)
                assertEquals("SubComponent: 6, updated", host.lastElementChild.textContent as String)
            }
        } catch (error: Throwable) {
            cleanup()
            throw error
        }

        return assertions.then(
            onFulfilled = { cleanup() },
            onRejected = { error ->
                cleanup()
                throw error
            }
        )
    }
}
