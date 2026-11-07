package kvue

import kvue.annotation.Emit
import kvue.annotation.Expose
import kvue.annotation.VueComponent
import kvue.`fun`.jso
import kvue.vdom.*
import kvue.vdom.knode.ElementNode
import kvue.vdom.knode.StringKNode
import kvue.vdom.knode.style
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.*
import vue.nextTick

@VueComponent
internal class BuiltInLeaf : Component() {
    @Emit private val changed: (Int) -> Unit by event()
    @Expose var count by ref(0)
    override val body = RenderFun {
        tag("button", count.toString()).apply {
            property("class", "built-in-leaf")
            property("style", "display: inline-flex")
            listener("onClick", { count++; changed(count) })
        }
    }
}

private fun RenderGroup.tag(name: String, text: String? = null): ElementNode = ElementNode(name).apply {
    if (text != null) slot("default") { addElement(StringKNode(text)) }
    addElement(this)
}

private class BuiltInHost(override val body: RenderFun) : Component()

private class FallbackOutlet(private val fallback: RenderFun) : Component() {
    override val body = RenderFun { slot("item", mapOf("value" to 7), fallback = fallback) }
}

private fun definition(name: String = "BuiltInHost", body: RenderFun) =
    ComponentDefinition(name, factory = { BuiltInHost(body) })

private class MountedBuiltIn(val host: HTMLElement, val app: vue.App) {
    private var mounted = true
    fun unmount() { if (mounted) { mounted = false; app.unmount() } }
    fun element(selector: String): HTMLElement = assertNotNull(host.querySelector(selector)) as HTMLElement
}

private fun withBuiltIn(
    body: RenderFun,
    configure: (vue.App) -> Unit = {},
    assertions: MountedBuiltIn.() -> Promise<Unit>,
): Promise<Unit> {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    val app = createApp(definition(body = body))
    val warnings = mutableListOf<String>()
    app.config.warnHandler = { warning, _, _ -> warnings += warning }
    configure(app)
    val mounted = MountedBuiltIn(host, app)
    fun cleanup() { mounted.unmount(); host.remove() }
    val result = try { app.mount(host); mounted.assertions() } catch (error: Throwable) { cleanup(); throw error }
    return result.then(
        onFulfilled = { cleanup(); assertTrue(warnings.isEmpty(), warnings.joinToString("\n")) },
        onRejected = { error -> cleanup(); throw error },
    )
}

private fun settleAsync(): Promise<Unit> = Promise { resolve, _ -> window.setTimeout({ resolve(Unit) }, 0) }

class BuiltInsTest {
    @Test
    fun slotFallbackHandlesMissingEmptyAndCommentOnlyContent(): Promise<Unit> {
        val mode = ref(0)
        var fallbackCalls = 0
        val outlet = ComponentDefinition("FallbackOutlet", factory = {
            FallbackOutlet(RenderFun { fallbackCalls++; tag("p", "fallback") })
        })
        return withBuiltIn(RenderFun {
            component(outlet).apply {
                if (mode.value > 0) slot("item") {
                    when (mode.value) {
                        2 -> addVNode(vue.createCommentVNode())
                        3 -> Fragment { addVNode(vue.createCommentVNode()) }
                        4 -> tag("p", slotProp<Int>("value").toString())
                    }
                }
            }
        }) {
            assertEquals("fallback", host.textContent)
            mode.value = 1
            nextTick().then {
                assertEquals("fallback", host.textContent)
                mode.value = 2
                nextTick()
            }.then {
                assertEquals("fallback", host.textContent)
                mode.value = 3
                nextTick()
            }.then {
                assertEquals("fallback", host.textContent)
                assertEquals(4, fallbackCalls)
                mode.value = 4
                nextTick()
            }.then {
                assertEquals("7", host.textContent)
                assertEquals(4, fallbackCalls)
            }
        }
    }

    @Test
    fun typedDirectivesAndShowReachSingleRootComponentsAndPreserveTheirInstance(): Promise<Unit> {
        val value = ref(1)
        val visible = ref(false)
        val child = BuiltInLeafVue.ref()
        val hooks = mutableListOf<String>()
        val updates = mutableListOf<Pair<Int, Int?>>()
        var emitted = 0
        val marker = directive<Int> {
            created = { _, binding, _, previous ->
                hooks += "created"
                assertEquals(1, binding.value)
                assertNull(binding.oldValue)
                assertNull(previous)
                assertEquals("counter", binding.argument)
                assertEquals(mapOf("exact" to true), binding.modifiers)
                assertNotNull(binding.instance)
            }
            beforeMount = { _, _, _, _ -> hooks += "beforeMount" }
            mounted = { element, _, _, _ -> hooks += "mounted"; element.setAttribute("data-directed", "yes") }
            beforeUpdate = { _, _, _, previous -> hooks += "beforeUpdate"; assertNotNull(previous) }
            updated = { _, binding, _, _ -> hooks += "updated"; updates += binding.value to binding.oldValue }
            beforeUnmount = { _, _, _, _ -> hooks += "beforeUnmount" }
            unmounted = { _, _, _, _ -> hooks += "unmounted" }
        }
        return withBuiltIn(RenderFun {
            BuiltInLeaf().directive(marker, value.value, "counter", mapOf("exact" to true))
                .show(visible.value).onChanged { emitted = it }.ref(child)
        }, configure = { app ->
            assertSame(app, app.directive("marker", marker))
            assertSame(marker.asVueDirective(), app.directive("marker"))
        }) {
            val instance = assertNotNull(child.value)
            val button = element("button")
            assertEquals("none", button.style.display)
            assertEquals("yes", button.getAttribute("data-directed"))
            assertEquals(listOf("created", "beforeMount", "mounted"), hooks)
            value.value = 2
            visible.value = true
            nextTick().then {
                assertSame(button, element("button"))
                assertSame(instance, child.value)
                assertEquals("inline-flex", button.style.display)
                assertEquals(2 to 1, updates.last())
                button.click()
                nextTick()
            }.then {
                assertEquals(1, emitted)
                assertEquals(1, instance.count)
                visible.value = false
                nextTick()
            }.then {
                assertSame(instance, child.value)
                assertEquals("none", button.style.display)
                unmount()
                assertNull(child.value)
                assertEquals(listOf("beforeUnmount", "unmounted"), hooks.takeLast(2))
            }
        }
    }

    @Test
    fun deepDirectivesTrackNestedValuesWithoutReadingThemInRender(): Promise<Unit> {
        val state = reactive(jso("count" to 0).unsafeCast<Any>())
        val seen = mutableListOf<Int>()
        val observe = directive<Any> {
            deep = true
            updated = { _, binding, _, _ -> seen += binding.value.asDynamic().count as Int }
        }
        return withBuiltIn(RenderFun { tag("div").directive(observe, state) }) {
            state.asDynamic().count = 1
            nextTick().then { assertEquals(listOf(1), seen) }
        }
    }

    @Test
    fun transitionRunsAppearEnterAndLeaveHooksWithoutFragmentWarnings(): Promise<Unit> {
        val shown = ref(true)
        val events = mutableListOf<String>()
        val hooks = TransitionHooks(
            onBeforeEnter = { events += "beforeEnter" },
            onEnter = { _, done -> events += "enter"; done() },
            onAfterEnter = { events += "afterEnter" },
            onLeave = { _, done -> events += "leave"; done() },
            onAfterLeave = { events += "afterLeave" },
        )
        return withBuiltIn(RenderFun {
            Transition(appear = true, css = false, hooks = hooks) { if (shown.value) BuiltInLeaf() }
        }) {
            assertEquals(listOf("beforeEnter", "enter", "afterEnter"), events)
            shown.value = false
            nextTick().then {
                assertNull(host.querySelector("button"))
                assertEquals(listOf("leave", "afterLeave"), events.takeLast(2))
                shown.value = true
                nextTick()
            }.then {
                assertNotNull(host.querySelector("button"))
                assertEquals(listOf("beforeEnter", "enter", "afterEnter"), events.takeLast(3))
            }
        }
    }

    @Test
    fun persistedTransitionCancelsLeaveWhenShowBecomesTrueAgain(): Promise<Unit> {
        val shown = ref(true)
        val events = mutableListOf<String>()
        var finishLeave: (() -> Unit)? = null
        val hooks = TransitionHooks(
            onEnter = { _, done -> events += "enter"; done() },
            onLeave = { _, done -> events += "leave"; finishLeave = done },
            onLeaveCancelled = { events += "cancelled" },
        )
        return withBuiltIn(RenderFun {
            Transition(css = false, persisted = true, hooks = hooks) { BuiltInLeaf().show(shown.value) }
        }) {
            val button = element("button")
            shown.value = false
            nextTick().then {
                assertEquals(listOf("leave"), events)
                assertEquals("inline-flex", button.style.display)
                shown.value = true
                nextTick()
            }.then {
                assertSame(button, element("button"))
                assertEquals(listOf("leave", "cancelled", "enter"), events)
                finishLeave?.invoke()
                assertEquals("inline-flex", button.style.display)
            }
        }
    }

    @Test
    fun transitionGroupAnimatesReorderingKotlinComponentsAndKeepsTheirDom(): Promise<Unit> {
        val order = ref(listOf(1, 2))
        val css = document.createElement("style")
        css.textContent = ".kvue-test-move { transition: transform 10s linear; }"
        document.head!!.appendChild(css)
        val result = withBuiltIn(RenderFun {
            TransitionGroup(tag = "div", css = false, moveClass = "kvue-test-move") {
                order.value.forEach { key ->
                    BuiltInLeaf(key = key).style { this["display"] = "block"; this["height"] = "20px" }
                        .property("data-order", key)
                }
            }
        }) {
            val first = element("[data-order='1']")
            val second = element("[data-order='2']")
            first.offsetHeight
            order.value = listOf(2, 1)
            nextTick().then {
                assertSame(first, element("[data-order='1']"))
                assertSame(second, element("[data-order='2']"))
                assertEquals("2", host.querySelector("button")!!.getAttribute("data-order"))
                assertTrue(first.classList.contains("kvue-test-move"))
                assertTrue(second.classList.contains("kvue-test-move"))
            }
        }
        return result.then(onFulfilled = { css.remove() }, onRejected = { css.remove(); throw it })
    }

    @Test
    fun explicitFragmentsKeepKeyedChildrenWhenRootCountChanges(): Promise<Unit> {
        val extra = ref(false)
        val stable = BuiltInLeafVue.ref()
        return withBuiltIn(RenderFun {
            Fragment {
                if (extra.value) BuiltInLeaf(key = "extra")
                BuiltInLeaf(key = "stable").ref(stable)
            }
        }) {
            val first = assertNotNull(stable.value)
            first.count = 9
            extra.value = true
            nextTick().then {
                assertSame(first, stable.value)
                assertEquals(2, host.querySelectorAll("button").length)
                extra.value = false
                nextTick()
            }.then {
                assertSame(first, stable.value)
                assertEquals("9", element("button").textContent)
            }
        }
    }

    @Test
    fun teleportSupportsLateTargetsDisabledSwitchesAndElementTargets(): Promise<Unit> {
        val disabled = ref(false)
        val outside = document.createElement("div")
        document.body!!.appendChild(outside)
        val result = withBuiltIn(RenderFun {
            Fragment {
                Teleport(to = "#kvue-late-target", defer = true, disabled = disabled.value) { tag("button", "late") }
                tag("div").property("id", "kvue-late-target")
                Teleport(to = outside) { tag("span", "outside") }
            }
        }) {
            val target = element("#kvue-late-target")
            val button = assertNotNull(target.querySelector("button"))
            assertEquals("outside", outside.textContent)
            disabled.value = true
            nextTick().then {
                assertNull(target.querySelector("button"))
                assertSame(button, host.querySelector("button"))
                disabled.value = false
                nextTick()
            }.then {
                assertSame(button, target.querySelector("button"))
                unmount()
                assertEquals("", outside.textContent)
            }
        }
        return result.then(onFulfilled = { outside.remove() }, onRejected = { outside.remove(); throw it })
    }

    @Test
    fun keepAliveRetainsInstancesAndRunsActivationHooks(): Promise<Unit> {
        var constructions = 0
        val events = mutableListOf<String>()
        val first = ComponentDefinition("CachedFirst", factory = {
            constructions++
            onActivated { events += "activated" }
            onDeactivated { events += "deactivated" }
            BuiltInHost(RenderFun { BuiltInLeaf() })
        })
        val second = definition("CachedSecond", RenderFun { tag("p", "second") })
        val active = shallowRef<VueComponentType>(first)
        return withBuiltIn(RenderFun {
            KeepAlive(include = KeepAliveFilter.anyOf(KeepAliveFilter.names("CachedFirst"), KeepAliveFilter.pattern("Second$")), max = 2) {
                component(active.value)
            }
        }) {
            val button = element("button")
            button.click()
            active.value = second
            nextTick().then {
                assertEquals("second", host.textContent)
                assertEquals(listOf("activated", "deactivated"), events)
                active.value = first
                nextTick()
            }.then {
                assertSame(button, element("button"))
                assertEquals("1", button.textContent)
                assertEquals(1, constructions)
                assertEquals(listOf("activated", "deactivated", "activated"), events)
            }
        }
    }

    @Test
    fun keepAliveHonorsExclusionAndLruLimit(): Promise<Unit> {
        val created = mutableMapOf<String, Int>()
        fun cached(name: String) = ComponentDefinition(name, factory = {
            created[name] = (created[name] ?: 0) + 1
            BuiltInHost(RenderFun { tag("p", name) })
        })
        val first = cached("First")
        val second = cached("Second")
        val excluded = cached("Excluded")
        val active = shallowRef<VueComponentType>(first)
        return withBuiltIn(RenderFun {
            KeepAlive(exclude = KeepAliveFilter.names("Excluded"), max = 1) { component(active.value) }
        }) {
            active.value = second
            nextTick().then { active.value = first; nextTick() }
                .then { assertEquals(2, created["First"]); active.value = excluded; nextTick() }
                .then { active.value = first; nextTick() }
                .then { active.value = excluded; nextTick() }
                .then { assertEquals(2, created["Excluded"]); assertEquals(2, created["First"]) }
        }
    }

    @Test
    fun suspenseUsesNamedFallbackAndResolvesTypedAsyncComponents(): Promise<Unit> {
        lateinit var resolve: (VueComponentType) -> Unit
        val events = mutableListOf<String>()
        var fallbackCalls = 0
        val loaded = definition("Loaded", RenderFun { tag("p", "loaded") })
        val async = defineAsyncComponent { Promise { complete, _ -> resolve = complete } }
        return withBuiltIn(RenderFun {
            Suspense(timeout = 0, onPending = { events += "pending" }, onFallback = { events += "fallback" }, onResolve = { events += "resolve" }) {
                component(async)
                slots.fallback { fallbackCalls++; tag("p", "loading") }
            }
        }) {
            assertEquals("loading", host.textContent)
            assertEquals(listOf("pending", "fallback"), events)
            assertEquals(1, fallbackCalls)
            resolve(loaded)
            settleAsync().then {
                assertEquals("loaded", host.textContent)
                assertEquals(listOf("pending", "fallback", "resolve"), events)
            }
        }
    }

    @Test
    fun asyncComponentsKeepLoadingOptionsAndRetryCallbacks(): Promise<Unit> {
        var attempts = 0
        val retries = mutableListOf<Int>()
        val loaded = definition("AsyncLoaded", RenderFun { tag("p", "ready") })
        val loading = definition("AsyncLoading", RenderFun { tag("p", "waiting") })
        val async = defineAsyncComponent(AsyncComponentOptions(
            loadingComponent = loading, delay = 0, suspensible = false,
            onError = { _, retry, _, attempt -> retries += attempt; retry() },
        )) {
            attempts++
            if (attempts == 1) Promise.reject(IllegalStateException("retry me")) else Promise.resolve(loaded)
        }
        return withBuiltIn(RenderFun { component(async) }) {
            assertEquals("waiting", host.textContent)
            settleAsync().then {
                assertEquals("ready", host.textContent)
                assertEquals(listOf(1), retries)
                assertEquals(2, attempts)
            }
        }
    }

    @Test
    fun dynamicComponentNamesForwardPropsAndLazyNamedSlots(): Promise<Unit> {
        val label = ref("first")
        val foreign = vue.defineComponent(jso("props" to arrayOf("label"), "setup" to { props: dynamic, context: dynamic ->
            { vue.h("section", null, arrayOf(vue.h("p", props.label), context.slots.header())) }
        }))
        return withBuiltIn(RenderFun {
            component("NamedPanel", props = mapOf("label" to label.value)) {
                slot("header") { tag("h2", label.value) }
            }
        }, configure = { it.component("NamedPanel", foreign) }) {
            assertEquals("firstfirst", host.textContent)
            label.value = "second"
            nextTick().then { assertEquals("secondsecond", host.textContent) }
        }
    }
}
