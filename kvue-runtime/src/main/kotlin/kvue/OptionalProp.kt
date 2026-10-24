package kvue

/** Distinguishes an omitted nullable prop from an explicitly supplied null. */
sealed interface OptionalProp<out T> {
    data object Omitted : OptionalProp<Nothing>
    data class Value<T>(val value: T) : OptionalProp<T>
}

fun <T> provided(value: T): OptionalProp<T> = OptionalProp.Value(value)
