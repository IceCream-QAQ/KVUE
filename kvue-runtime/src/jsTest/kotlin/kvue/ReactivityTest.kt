package kvue

import kvue.`fun`.jso
import kotlin.js.Promise
import kotlin.test.*
import vue.nextTick

private class DelegateState {
    var count by ref(1)
    var name by ref<String?>(null)
}

private external interface TreeState {
    var count: Int
    var child: TreeChild
}
private external interface TreeChild { var value: Int }

class ReactivityTest {
    @Test
    fun refsWorkAsHandlesAndDelegatesAndComputedValuesStayLazy() {
        val handle = ref(1)
        var local by handle
        var evaluations = 0
        val doubled = computed { evaluations++; local * 2 }
        val result by doubled
        assertEquals(0, evaluations)
        assertEquals(2, result)
        assertEquals(2, result)
        assertEquals(1, evaluations)
        local = 3
        assertEquals(3, handle.value)
        assertEquals(1, evaluations)
        assertEquals(6, result)
        assertEquals(2, evaluations)

        var writable by computed(get = { local * 2 }, set = { local = it / 2 })
        writable = 10
        assertEquals(5, local)
        assertTrue(isReadonly(doubled))
        assertTrue(isRef(handle))
        assertEquals(5, unref(handle))
        assertEquals(5, unref(handle.asVueRef()))
        assertEquals(9, unref(9))
        assertEquals(10, toValue { result })

        val holder = DelegateState()
        val bridge = toRef(holder::count)
        bridge.value = 7
        assertEquals(7, holder.count)
        val getterRef = toRef { holder.count + 1 }
        assertTrue(isReadonly(getterRef))
        assertEquals(8, getterRef.value)

        // A ref facade stored in reactive state must not have its internal native ref auto-unwrapped.
        val box = reactive(jso("handle" to handle))
        val sameHandle = box.handle.unsafeCast<kvue.reactive.RefValue<Int>>()
        assertTrue(sameHandle === handle)
        sameHandle.value = 6
        assertEquals(6, local)
    }

    @Test
    fun watchSupportsPropertyReferencesInitialNullCleanupPauseAndStop() {
        val state = DelegateState()
        val events = mutableListOf<String>()
        var cleanups = 0
        var globalCleanups = 0
        val handle = watch(state::name, WatchOptions(immediate = true, flush = WatchFlush.Sync)) { value, old ->
            events += "$value|$old|$isInitial"
            onCleanup { cleanups++ }
            onWatcherCleanup { globalCleanups++ }
        }
        try {
            assertEquals(listOf("null|null|true"), events)
            state.name = "first"
            assertEquals("first|null|false", events.last())
            assertEquals(1, cleanups)
            handle.pause()
            state.name = "paused"
            state.name = "latest"
            assertEquals(2, events.size)
            handle.resume()
            assertEquals("latest|first|false", events.last())
            state.name = null
            assertEquals("null|latest|false", events.last())
            handle()
            state.name = "stopped"
            assertEquals(4, events.size)
            assertEquals(4, cleanups)
            assertEquals(cleanups, globalCleanups)
        } finally { handle.stop() }
    }

    @Test
    fun getterWatchesTrackOnlyReadsAndMultipleSourcesKeepPreviousValues(): Promise<Unit> {
        val state = DelegateState()
        val changes = mutableListOf<Pair<Int, Int?>>()
        val snapshots = mutableListOf<Pair<List<Any?>, List<Any?>?>>()
        val getter = watch({ state.count * 2 }) { value, old -> changes += value to old }
        val multiple = watchMany(state::count, state::name, options = WatchOptions(immediate = true)) { values, old ->
            snapshots += values to old
        }
        assertEquals(listOf(1, null), snapshots.single().first)
        assertNull(snapshots.single().second)
        state.count = 2
        state.count = 3
        state.name = "new"
        assertTrue(changes.isEmpty())
        return nextTick().then {
            assertEquals(listOf<Pair<Int, Int?>>(6 to 2), changes)
            assertEquals(listOf<Any?>(3, "new"), snapshots.last().first)
            assertEquals(listOf<Any?>(1, null), snapshots.last().second)
            state.name = "other"
            nextTick()
        }.then {
            assertEquals(1, changes.size)
            assertEquals(listOf(3, "other"), snapshots.last().first)
        }.then(
            onFulfilled = { getter.stop(); multiple.stop() },
            onRejected = { error -> getter.stop(); multiple.stop(); throw error },
        )
    }

    @Test
    fun shallowAndCustomRefsRetainTheirNativeNotificationRules() {
        val scope = effectScope()
        try {
            scope.run {
                val state = shallowRef(jso("value" to 1))
                var calls = 0
                watch(state, WatchOptions(flush = WatchFlush.Sync)) { value, old ->
                    calls++
                    assertTrue(value === old)
                }
                state.value.value = 2
                assertEquals(0, calls)
                triggerRef(state)
                assertEquals(1, calls)
                assertTrue(isShallow(state))
                assertFalse(isReactive(state.value))

                var stored = 1
                var notify: () -> Unit = {}
                val custom = customRef<Int> { track, trigger ->
                    notify = trigger
                    RefAccessor(get = { track(); stored }, set = { stored = it })
                }
                val updates = mutableListOf<Int>()
                watch(custom, WatchOptions(flush = WatchFlush.Sync)) { value, _ -> updates += value }
                custom.value = 8
                assertTrue(updates.isEmpty())
                notify()
                assertEquals(listOf(8), updates)
            }
        } finally { scope.stop() }
    }

    @Test
    fun reactiveObjectsHonorDepthAndPropertyRefs() {
        val scope = effectScope()
        try {
            scope.run {
                val original = jso("count" to 0, "child" to jso("value" to 0)).unsafeCast<TreeState>()
                val state = reactive(original)
                var deepCalls = 0
                var rootCalls = 0
                watchReactive(state, WatchOptions(flush = WatchFlush.Sync)) { value, old ->
                    deepCalls++
                    assertTrue(value === old)
                }
                watchReactive(state, WatchOptions(depth = 1, flush = WatchFlush.Sync)) { _, _ -> rootCalls++ }
                state.child.value = 1
                assertEquals(1, deepCalls)
                assertEquals(0, rootCalls)
                state.child = jso("value" to 2).unsafeCast<TreeChild>()
                assertEquals(2, deepCalls)
                assertEquals(1, rootCalls)
                toRef<Int>(state, "count").value = 4
                assertEquals(4, state.count)
                toRefs(state).getValue("count").value = 5
                assertEquals(5, state.count)
                assertEquals(7, toRef(state, "missing", 7).value)
                assertTrue(toRaw(state) === original)
                assertTrue(isReactive(state))
                assertTrue(isProxy(state))
                assertTrue(isReadonly(readonly(state)))
                val shallow = shallowReactive(jso("child" to jso("value" to 0)))
                assertFalse(isReactive(shallow.child))
                val raw = markRaw(jso("count" to 0))
                assertTrue(reactive(raw) === raw)
            }
        } finally { scope.stop() }
    }

    @Test
    fun effectScopesOwnWatcherCleanupAndCanPauseAndResume() {
        val source = ref(0)
        val scope = effectScope()
        val values = mutableListOf<Int>()
        var cleanups = 0
        var disposed = 0
        try {
            scope.run {
                assertTrue(getCurrentScope()!!.active)
                onScopeDispose { disposed++ }
                watchSyncEffect {
                    values += source.value
                    onCleanup { cleanups++ }
                }
            }
            assertNull(getCurrentScope())
            source.value = 1
            scope.pause()
            source.value = 2
            assertEquals(listOf(0, 1), values)
            scope.resume()
            assertEquals(listOf(0, 1, 2), values)
            scope.stop()
            assertFalse(scope.active)
            source.value = 3
            assertEquals(3, cleanups)
            assertEquals(1, disposed)
            assertEquals(listOf(0, 1, 2), values)
        } finally { scope.stop() }
    }

    @Test
    fun postEffectsWaitForFlushAndOnceWatchersStopThemselves(): Promise<Unit> {
        val source = ref(0)
        val values = mutableListOf<Int>()
        var onceCalls = 0
        val post = watchPostEffect { values += source.value }
        val once = watch(source, WatchOptions(once = true, flush = WatchFlush.Sync)) { _, _ -> onceCalls++ }
        assertTrue(values.isEmpty())
        source.value = 1
        source.value = 2
        assertEquals(1, onceCalls)
        return nextTick().then {
            assertEquals(listOf(2), values)
        }.then(
            onFulfilled = { post.stop(); once.stop() },
            onRejected = { error -> post.stop(); once.stop(); throw error },
        )
    }
}
