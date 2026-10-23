package kvue.`fun`

private external fun k2vue(n: String?, o: dynamic): dynamic

fun k2v(data: Any): dynamic {
    return k2vue(data::class.simpleName, data)
}