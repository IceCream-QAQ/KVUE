package kvue

private object OmittedBinding

/** A compiler-only argument default, distinct from every value including null. */
fun <T> omittedBinding(): T = OmittedBinding.unsafeCast<T>()

fun checkBindingArguments(vararg arguments: Any?) {
    check(arguments.all { it === OmittedBinding }) {
        "KVUE binding arguments require the KVUE Kotlin compiler plugin. Apply the org.kvue Gradle plugin."
    }
}

fun <T> requiredBindingProp(name: String): T =
    error("KVUE requires '$name' or its binding argument.")

/** Rest-event adapters collect their JS payloads into an array; update events use the first value. */
fun bindingUpdateValue(arguments: Array<Any?>): Any? {
    check(arguments.isNotEmpty()) { "KVUE update event did not supply a value." }
    return arguments[0]
}
