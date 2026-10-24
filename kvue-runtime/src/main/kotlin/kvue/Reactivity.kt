package kvue

import kvue.`fun`.jso
import kvue.reactive.ReadRef
import kvue.reactive.ReadonlyRef
import kvue.reactive.RefValue
import kotlin.reflect.KMutableProperty0

fun <T> ref(value: T): RefValue<T> = RefValue(value)
fun <T> shallowRef(value: T): RefValue<T> = RefValue(vue.shallowRef(value))
fun triggerRef(ref: ReadRef<*>) = vue.triggerRef(ref.vueRef)

fun <T> computed(getter: () -> T): ReadonlyRef<T> = ReadonlyRef(vue.computed(getter))
fun <T> computed(get: () -> T, set: (T) -> Unit): RefValue<T> =
    RefValue(vue.computed<T>(jso("get" to get, "set" to set)))

data class RefAccessor<T>(val get: () -> T, val set: (T) -> Unit)

fun <T> customRef(factory: (track: () -> Unit, trigger: () -> Unit) -> RefAccessor<T>): RefValue<T> =
    RefValue(vue.customRef<T> { track, trigger ->
        val accessor = factory(track, trigger)
        jso("get" to accessor.get, "set" to accessor.set)
    })

fun <T : Any> reactive(value: T): T = vue.reactive(value)
fun <T : Any> shallowReactive(value: T): T = vue.shallowReactive(value)
fun <T : Any> readonly(value: T): T = vue.readonly(value)
fun <T : Any> shallowReadonly(value: T): T = vue.shallowReadonly(value)
fun <T> readonly(ref: ReadRef<T>): ReadonlyRef<T> = ReadonlyRef(vue.readonly(ref.vueRef).unsafeCast<vue.Ref<T>>())
fun <T> shallowReadonly(ref: ReadRef<T>): ReadonlyRef<T> = ReadonlyRef(vue.shallowReadonly(ref.vueRef).unsafeCast<vue.Ref<T>>())
fun <T> toRaw(value: T): T = vue.toRaw(value)
fun <T : Any> markRaw(value: T): T = vue.markRaw(value)

private fun refTarget(value: Any?): Any? = if (value is ReadRef<*>) value.vueRef else value
fun isRef(value: Any?): Boolean = vue.isRef(refTarget(value))
fun isReactive(value: Any?): Boolean = vue.isReactive(refTarget(value))
fun isReadonly(value: Any?): Boolean = vue.isReadonly(refTarget(value))
fun isShallow(value: Any?): Boolean = vue.isShallow(refTarget(value))
fun isProxy(value: Any?): Boolean = vue.isProxy(refTarget(value))

fun <T> unref(value: ReadRef<T>): T = value.value
fun <T> unref(value: vue.Ref<T>): T = value.value
fun <T> unref(value: T): T = value
fun <T> toValue(source: () -> T): T = source()
fun <T> toValue(source: ReadRef<T>): T = source.value
fun <T> toValue(source: vue.Ref<T>): T = source.value
fun <T> toValue(value: T): T = value

fun <T> toRef(getter: () -> T): ReadonlyRef<T> = ReadonlyRef(vue.toRef<T>(getter))
fun <T> toRef(property: KMutableProperty0<T>): RefValue<T> = computed(property::get, property::set)
fun <T> toRef(ref: ReadRef<T>): ReadRef<T> = ref
fun <T> toRef(ref: RefValue<T>): RefValue<T> = ref

/** key is a native JS property name; this does not reflect Kotlin's compiled backing-field names. */
fun <T> toRef(target: Any, key: String): RefValue<T> = RefValue(vue.toRef<T>(target, key))
fun <T> toRef(target: Any, key: String, defaultValue: T): RefValue<T> = RefValue(vue.toRef<T>(target, key, defaultValue))

/** Mapped object types become a map of explicitly untyped property refs. */
fun toRefs(target: Any): Map<String, RefValue<Any?>> {
    val refs = vue.toRefs(target)
    val names = js("Object.keys")(refs).unsafeCast<Array<String>>()
    return names.associateWith { name -> RefValue(refs[name].unsafeCast<vue.Ref<Any?>>()) }
}

class EffectScope internal constructor(private val scope: vue.EffectScope) {
    val active: Boolean get() = scope.active
    fun <T> run(block: () -> T): T? {
        val result = scope.run(block)
        return if (jsTypeOf(result) == "undefined") null else result
    }
    fun stop() = scope.stop()
    fun pause() = scope.pause()
    fun resume() = scope.resume()
}

fun effectScope(detached: Boolean = false): EffectScope = EffectScope(vue.effectScope(detached))
fun getCurrentScope(): EffectScope? {
    val scope = vue.getCurrentScope()
    return if (scope == null) null else EffectScope(scope)
}
fun onScopeDispose(callback: () -> Unit) = vue.onScopeDispose(callback)
