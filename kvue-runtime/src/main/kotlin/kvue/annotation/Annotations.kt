package kvue.annotation

/** Generates a Vue definition and a RenderGroup extension named after this class. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class VueComponent

/** Schema for an existing JS export. Usually generated from a library's TypeScript declarations. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class JsComponent(val module: String, val exportName: String = "default")

/** Defaults are evaluated by property(...) in each component instance. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Prop(val required: Boolean = false, val name: String = "", val variadic: Boolean = false)

/** The property must be a delegated, non-null function returning Unit. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Emit(val name: String = "", val variadic: Boolean = false)
