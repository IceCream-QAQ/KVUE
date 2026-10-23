package test

import vue.createApp

fun main() {

    createApp(App().buildVueComponent())
        .mount("#app")
//    createApp(app())
//        .mount("#app")
//    createApp(RuntimeComponent("app") { { arrayOf(h("div", null, arrayOf("Hello Vue!"))) } })
//        .mount("#app")
}

//val b by component { a: Int, b: Int ->
//
//}


//fun <T : KFunction<Unit>> component(
//    name: String? = null,
//    body: T
//): ReadOnlyProperty<Nothing?, T> = Component(name, body)
//
//class Component<T : KFunction<Unit>>(val name: String?, val body: T) :
//    ReadOnlyProperty<Nothing?, T> {
//
//    private var value: T? = null
//
//    override fun getValue(thisRef: Nothing?, property: KProperty<*>): T {
//        return value!!
//    }
//}
//
//private operator fun Any.getValue(nothing: Nothing?, property: KProperty<*>): ComponentInstance {
//    TODO("Not yet implemented")
//}
//
//fun app() = temple {
//    tdiv("Hello Vue! - From Kotlin/JS!")
//    div {
//        val num by ref(arrayOf("Hello", "Vue"))
//        tdiv("Now array size is: ${num.size}.")
//        button("Click me!") {
//            num.push(num.size.toString())
//        }
//        num.asSequence().filter { it.toInt() < 10 }
//            .forEach {
//                tdiv(it)
//            }
//    }
//}

//val a: VElement.() -> Unit = {}

