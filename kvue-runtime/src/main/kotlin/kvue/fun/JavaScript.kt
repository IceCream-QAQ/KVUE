package kvue.`fun`


fun jso(vararg pairs: Pair<dynamic, dynamic>): dynamic {
    val o = js("{}")
    for (pair in pairs) {
        o[pair.first] = pair.second
    }
    return o
}

fun Map<*,*>.toJavaScriptObject(): dynamic {
    val o = js("{}")
    for ((k, v) in this) {
        o[k] = v
    }
    return o
}