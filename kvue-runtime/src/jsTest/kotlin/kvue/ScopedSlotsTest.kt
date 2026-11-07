package kvue

import kvue.annotation.Slot
import kvue.annotation.SlotProp
import kvue.annotation.VueComponent
import kvue.vdom.RenderFun
import kvue.vdom.RenderGroup
import kvue.vdom.knode.ElementNode
import kvue.vdom.knode.StringKNode
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import vue.nextTick

private fun RenderGroup.text(value: String) = addElement(StringKNode(value))
private fun RenderGroup.tag(name: String, content: RenderFun) =
    ElementNode(name).apply { slot("default", content) }.also(::addElement)

internal data class SlotRow(val name: String)
internal interface RowFields<T> { val row: T }

@VueComponent
internal class ScopedRows : Component() {
    @Slot("item")
    interface Item : RowFields<SlotRow> {
        val index: Int
        val note: String?
        @SlotProp("slotProps") val rawName: String
    }
    @Slot("default") interface Default { val total: Int }
    private var visible by ref(false)
    private var version by ref(0)
    override val body = RenderFun {
        tag("button") { text("show") }.apply { property("data-show", ""); listener("onClick", { visible = true }) }
        tag("button") { text("change") }.apply { property("data-change", ""); listener("onClick", { version++ }) }
        if (visible) {
            slot(props = mapOf("total" to 2))
            repeat(2) { index ->
                slot("item", mapOf("row" to SlotRow("row-$version-$index"), "index" to index,
                    "note" to null, "slotProps" to "original"))
            }
            slot("raw", mapOf("message" to "raw-$version"))
            slot("empty")
        }
    }
}

internal object ScopedProbe { var calls = 0 }

@VueComponent
internal class ScopedHost : Component() {
    override val body = RenderFun {
        ScopedRows {
            slots.default { tag("p") { text("total:" + this@default.total) }.property("data-total", "") }
            slots.item {
                ScopedProbe.calls++
                val item = this.row
                val position = index
                val message = "$note:$rawName"
                tag("p") { text("${item.name}:$position:$message") }.property("data-row", position)
            }
            slot("raw") { text(slotProps.message as String) }
            slot("raw") { text(":appended") }
            slot("empty", RenderFun { text(":reused") })
        }
    }
}

class ScopedSlotsTest {
    @Test fun receiversKeepPropsPerInvocationAndStayLazyAndReactive(): Promise<Unit> {
        ScopedProbe.calls = 0
        return withMounted(ScopedHostVue.definition) { host ->
            assertEquals(0, ScopedProbe.calls)
            assertNull(host.querySelector("[data-row]"))
            host.querySelector("[data-show]").click()
            nextTick().then {
                assertEquals("total:2", host.querySelector("[data-total]").textContent as String)
                assertEquals("row-0-0:0:null:original", host.querySelector("[data-row='0']").textContent as String)
                assertEquals("row-0-1:1:null:original", host.querySelector("[data-row='1']").textContent as String)
                assertEquals(true, (host.textContent as String).endsWith("raw-0:appended:reused"))
                host.querySelector("[data-change]").click()
                nextTick()
            }.then {
                assertEquals("row-1-0:0:null:original", host.querySelector("[data-row='0']").textContent as String)
                assertEquals("row-1-1:1:null:original", host.querySelector("[data-row='1']").textContent as String)
                assertEquals(true, (host.textContent as String).endsWith("raw-1:appended:reused"))
                assertEquals(4, ScopedProbe.calls)
            }
        }
    }
}
