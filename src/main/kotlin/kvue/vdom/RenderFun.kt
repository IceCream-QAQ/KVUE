package kvue.vdom

fun interface RenderFun {
    operator fun RenderContext.invoke()
}