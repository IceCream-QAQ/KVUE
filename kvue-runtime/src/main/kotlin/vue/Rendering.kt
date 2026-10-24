@file:JsModule("vue")
@file:JsNonModule

package vue

external val Fragment: dynamic
external val Text: dynamic
external val Comment: dynamic
external val Teleport: dynamic
external val Suspense: dynamic
external val KeepAlive: dynamic
external val Transition: dynamic
external val TransitionGroup: dynamic

external fun h(type: dynamic): VNode
external fun h(type: dynamic, propsOrChildren: dynamic): VNode
external fun h(type: dynamic, props: dynamic, children: dynamic): VNode
external fun createCommentVNode(text: String = definedExternally): VNode
external fun mergeProps(vararg props: dynamic): dynamic
external fun cloneVNode(vnode: VNode, extraProps: dynamic = definedExternally, mergeRef: Boolean = definedExternally): VNode
external fun isVNode(value: Any?): Boolean
external fun resolveComponent(name: String): dynamic
external fun resolveDynamicComponent(component: dynamic): dynamic
external fun resolveDirective(name: String): Directive?
external fun withModifiers(handler: dynamic, modifiers: Array<String>): dynamic
external fun withKeys(handler: dynamic, modifiers: Array<String>): dynamic
