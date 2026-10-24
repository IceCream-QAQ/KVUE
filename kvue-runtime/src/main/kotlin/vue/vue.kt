@file:JsModule("vue")
@file:JsNonModule

package vue

external interface Ref<T> {
    var value: dynamic
}

external interface VNode

external fun createApp(o: dynamic): dynamic

external fun <T> ref(data: T): Ref<T>


external fun createVNode(tag: dynamic, props: dynamic, children: dynamic): VNode

external fun createTextVNode(text: String): VNode




