package kvue.`fun`

inline fun <reified T> Array<T>.push(item: T): Int{
    return this.asDynamic().push(item) as Int
}