package kvue

import kvue.`fun`.jso
import kvue.fixtures.CompositionHostVue
import kvue.fixtures.CompositionProbe
import kvue.fixtures.ErrorBoundaryVue
import kotlin.js.Promise
import kotlin.test.*
import vue.nextTick

private external val document: dynamic

class CompositionTest {
    @Test
    fun classSetupSupportsInjectionLifecycleTemplateRefsAndAutomaticWatchDisposal(): Promise<Unit> {
        CompositionProbe.reset()
        return withCompositionMounted(CompositionHostVue.definition) { host ->
            assertTrue(CompositionProbe.currentInstancePresent)
            assertTrue(CompositionProbe.mountedRefPresent)
            assertEquals(listOf("child:before-mount", "child:mounted", "host:mounted"), CompositionProbe.events)
            assertEquals("0|0", host.querySelector("output").textContent as String)
            assertEquals("attrs", host.querySelector("output").getAttribute("data-probe") as String)
            assertTrue(CompositionProbe.childId.isNotBlank())
            assertTrue(CompositionProbe.childElement.value === host.querySelector("output"))
            CompositionProbe.count.value = 2
            nextTick().then {
                assertEquals(listOf("2|4"), CompositionProbe.postRender)
                assertEquals(listOf(0, 2), CompositionProbe.effects)
                assertEquals(1, CompositionProbe.cleanups)
                assertTrue("child:updated" in CompositionProbe.events)
                CompositionProbe.visible.value = false
                nextTick()
            }.then {
                assertNull(host.querySelector("output"))
                assertTrue(CompositionProbe.unmountedRefCleared)
                assertTrue("child:before-unmount" in CompositionProbe.events)
                assertTrue("child:disposed" in CompositionProbe.events)
                assertEquals(2, CompositionProbe.cleanups)
                CompositionProbe.count.value = 3
                nextTick()
            }.then {
                assertEquals(listOf(0, 2), CompositionProbe.effects)
                assertEquals(listOf("2|4"), CompositionProbe.postRender)
            }
        }
    }

    @Test
    fun typedInjectionKeysKeepIdentityDefaultsAndAppContext() {
        val app = vue.createApp(jso())
        val first = injectionKey<String?>("same description")
        val second = injectionKey<String?>("same description")
        val absent = injectionKey<String>("absent")
        assertEquals("symbol", jsTypeOf(first))
        assertTrue(first !== second)
        app.provide(first, null)
        app.provide(second, "second")
        assertFalse(hasInjectionContext())
        val result = app.runWithContext {
            assertTrue(hasInjectionContext())
            assertNull(inject(first))
            assertNull(requireInject(first))
            assertNull(injectFactory(first) { error("A provided null must not invoke the default factory") })
            assertEquals("second", requireInject(second))
            assertNull(inject(absent))
            assertEquals("fallback", inject(absent, "fallback"))
            assertEquals("factory", injectFactory(absent) { "factory" })
            assertFailsWith<IllegalStateException> { requireInject(absent) }
            42
        }
        assertEquals(42, result)
        assertFalse(hasInjectionContext())
    }

    @Test
    fun errorCapturedCanStopPropagationFromChildRendering(): Promise<Unit> {
        CompositionProbe.reset()
        return withCompositionMounted(ErrorBoundaryVue.definition) {
            assertTrue(CompositionProbe.error.startsWith("render failed|"))
            nextTick().then { assertTrue(CompositionProbe.error.isNotBlank()) }
        }
    }

    @Test
    fun nativeRenderHelpersAndApplicationRegistrationKeepTheirJsShape() {
        val original = vue.h("p", jso("class" to "first"), "native")
        val clone = vue.cloneVNode(original, jso("class" to "second"))
        assertTrue(vue.isVNode(original))
        assertTrue(vue.isVNode(clone))
        assertTrue(original !== clone)
        assertEquals("first second", clone.asDynamic().props["class"] as String)
        val render: () -> vue.VNode = { clone }
        val component = vue.defineComponent(jso("render" to render))
        val app = vue.createApp(component)
        assertTrue(app.component("Named", component) === app)
        assertTrue(app.component("Named") === component)
        var unmounted = false
        app.onUnmount { unmounted = true }
        val host = document.createElement("div")
        document.body.appendChild(host)
        try {
            app.mount(host)
            assertEquals("native", host.textContent as String)
        } finally { app.unmount(); host.remove() }
        assertTrue(unmounted)
    }

    @Test
    fun asyncWatchResultsReachVueErrorHandlingAndStillCleanUp(): Promise<Unit> {
        val source = ref(0)
        var failure: String? = null
        var cleanups = 0
        val setup: () -> (() -> vue.VNode) = {
            watch(source) { _, _ ->
                onCleanup { cleanups++ }
                Promise<Unit> { _, reject -> reject(IllegalStateException("async watch")) }
            }
            val render: () -> vue.VNode = { vue.h("div") }
            render
        }
        val app = vue.createApp(vue.defineComponent(jso("setup" to setup)))
        app.config.errorHandler = { error, _, _ -> failure = error.asDynamic().message as String }
        val host = document.createElement("div")
        document.body.appendChild(host)
        app.mount(host)
        source.value = 1
        fun cleanup() { app.unmount(); host.remove() }
        return nextTick().then {
            assertEquals("async watch", failure)
        }.then(
            onFulfilled = { cleanup(); assertEquals(1, cleanups) },
            onRejected = { error -> cleanup(); throw error },
        )
    }
}

private fun withCompositionMounted(definition: VueComponentType, assertions: (dynamic) -> Promise<Unit>): Promise<Unit> {
    val host = document.createElement("div")
    document.body.appendChild(host)
    val app = createApp(definition)
    fun cleanup() { app.unmount(); host.remove() }
    val result = try { app.mount(host); assertions(host) } catch (error: Throwable) { cleanup(); throw error }
    return result.then(onFulfilled = { cleanup() }, onRejected = { error -> cleanup(); throw error })
}
