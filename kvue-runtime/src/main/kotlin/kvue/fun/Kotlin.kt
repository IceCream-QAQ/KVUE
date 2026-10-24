package kvue.`fun`

fun obj(vararg pairs: Pair<String, dynamic>): dynamic {
    val o = js("{}")
    for (pair in pairs) {
        o[pair.first] = pair.second
    }
    return o
}