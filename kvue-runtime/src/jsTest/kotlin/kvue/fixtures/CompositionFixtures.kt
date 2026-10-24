package kvue.fixtures

import kvue.*
import kvue.annotation.VueComponent
import kvue.reactive.ReadRef
import kvue.reactive.ReadonlyRef
import kvue.vdom.RenderFun
import kvue.vdom.knode.ElementNode
import kvue.vdom.knode.StringKNode

internal object CompositionProbe {
    val countKey = injectionKey<ReadRef<Int>>("shared count")
    var count = ref(0)
    var visible = ref(true)
    val events = mutableListOf<String>()
    val effects = mutableListOf<Int>()
    val postRender = mutableListOf<String>()
    var cleanups = 0
    var currentInstancePresent = false
    var mountedRefPresent = false
    var unmountedRefCleared = false
    var childId = ""
    var error = ""
    lateinit var childElement: ReadonlyRef<Any?>

    fun reset() {
        count = ref(0)
        visible = ref(true)
        events.clear(); effects.clear(); postRender.clear()
        cleanups = 0
        currentInstancePresent = false
        mountedRefPresent = false
        unmountedRefCleared = false
        childId = ""
        error = ""
    }
}

@VueComponent
internal class CompositionHost : Component() {
    private val visible by CompositionProbe.visible

    init {
        provide(CompositionProbe.countKey, CompositionProbe.count)
        onMounted { CompositionProbe.events += "host:mounted" }
    }

    override val body = RenderFun {
        if (visible) CompositionChild().property("data-probe", "attrs")
    }
}

@VueComponent
internal class CompositionChild : Component() {
    private val count by requireInject(CompositionProbe.countKey)
    private val doubled by computed { count * 2 }
    private val element = useTemplateRef<Any>("output")
    private fun readAttrs() = useAttrs()
    private val attrs = readAttrs()
    private val id = useId()

    init {
        CompositionProbe.currentInstancePresent = getCurrentInstance() != null
        CompositionProbe.childElement = element
        CompositionProbe.childId = id
        watch(::count, WatchOptions(flush = WatchFlush.Post)) { _, _ ->
            CompositionProbe.postRender += element.value.asDynamic().textContent as String
        }
        watchEffect {
            CompositionProbe.effects += count
            onCleanup { CompositionProbe.cleanups++ }
        }
        onBeforeMount { CompositionProbe.events += "child:before-mount" }
        onMounted {
            CompositionProbe.events += "child:mounted"
            CompositionProbe.mountedRefPresent = element.value != null
        }
        onBeforeUpdate { CompositionProbe.events += "child:before-update" }
        onUpdated { CompositionProbe.events += "child:updated" }
        onBeforeUnmount { CompositionProbe.events += "child:before-unmount" }
        onUnmounted {
            CompositionProbe.events += "child:unmounted"
            CompositionProbe.unmountedRefCleared = element.value == null
        }
        onScopeDispose { CompositionProbe.events += "child:disposed" }
    }

    override val body = RenderFun {
        addElement(ElementNode("output").apply {
            property("id", id)
            property("ref", "output")
            property("data-probe", attrs["data-probe"])
            slot("default") { addElement(StringKNode("$count|$doubled")) }
        })
    }
}

@VueComponent
internal class ErrorBoundary : Component() {
    init {
        onErrorCaptured { error, _, info ->
            CompositionProbe.error = "${error.asDynamic().message}|$info"
            false
        }
    }
    override val body = RenderFun { RenderFailure() }
}

@VueComponent
internal class RenderFailure : Component() {
    override val body = RenderFun { error("render failed") }
}
