@file:JsModule("vue")
@file:JsNonModule

package vue

import kotlin.js.Promise

external interface Ref<T> {
    var value: T
}

external interface VNode

external interface Directive

external fun createApp(rootComponent: dynamic, rootProps: dynamic = definedExternally): App

external fun createSSRApp(rootComponent: dynamic, rootProps: dynamic = definedExternally): App

external fun <T> ref(data: T): Ref<T>


external fun createVNode(tag: dynamic, props: dynamic, children: dynamic): VNode

external fun createTextVNode(text: String): VNode

external fun withDirectives(vnode: VNode, directives: Array<Array<Any?>>): VNode

external fun nextTick(): Promise<Unit>

external fun <T> nextTick(callback: () -> T): Promise<T>
