package kvue

import kvue.`fun`.jso
import kvue.`fun`.push
import kvue.reactive.PropertyValue
import kvue.reactive.RefValue
import kvue.vdom.RenderContext
import kvue.vdom.RenderFun
import vue.VueSetupFun
import kotlin.reflect.KClass

/*** 组件声明
 * 用于声明这是一个 UI 组件。
 */
abstract class Component {

    var vueProps: dynamic = null
    private var setupContext: dynamic = null

    fun <T> ref(value: T) = RefValue(value)

    fun <T> property(defaultValue: T): PropertyValue<T> = PropertyValue(defaultValue)

    abstract val body: RenderFun


    companion object{

        private val componentMap = mutableMapOf<KClass<out Component>, Any>()

        fun buildVueComponent(component: Component): dynamic {
            componentMap[component::class]?.let { return it }
            val inputProps = arrayOf<String>()
            js("Object").keys(component)
                .filter { key: String -> key.contains("\$delegate") }
                .forEach { key: String ->
                    fun readField(obj: dynamic, key: String): dynamic = obj[key]
                    val value = readField(component, key)
                    if (value is PropertyValue<*>) inputProps.push(key.split("\$")[0])
                }

            val setupFun: VueSetupFun = { props, context ->
                js("Object").keys(component)
                    .filter { key: String -> key.contains("\$delegate") }
                    .forEach { key: String ->
                        fun readField(obj: dynamic, key: String): dynamic = obj[key]
                        val value = readField(component, key)
                        if (value is RefValue<*>) value.ref = vue.ref(value.defaultValue)
                    }
                component.vueProps = props
                component.setupContext = context
                {
                    component.body.run { RenderContext().apply { invoke() } }.result()
                        .map { it.buildVNode() }
                        .toTypedArray()
                }
            }

            val componentObj = jso(
                "name" to component::class.simpleName,
                "props" to inputProps,
                "setup" to setupFun
            )
            componentMap[component::class] = componentObj
            return componentObj
        }
    }

    fun buildVueComponent(): dynamic = buildVueComponent(this)

}


