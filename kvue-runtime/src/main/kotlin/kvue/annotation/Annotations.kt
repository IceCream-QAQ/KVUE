package kvue.annotation

/** Generates a Vue definition and a RenderGroup extension named after this class. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class VueComponent

/** Schema for an existing JS export. Usually generated from a library's TypeScript declarations. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class JsComponent(val module: String, val exportName: String = "default", val hasInstance: Boolean = true)

/** Defaults are evaluated by property(...) in each component instance. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Prop(val required: Boolean = false, val name: String = "", val variadic: Boolean = false)

/** The property must be a delegated, non-null function returning Unit. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Emit(val name: String = "", val variadic: Boolean = false)

/** Public instance API. JS schemas may use variadic for signatures widened to an argument array. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Expose(val name: String = "", val variadic: Boolean = false)

/** An imported JS argument can be omitted rather than passed as null. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class JsOptional

/** Declares named slots exposed by a component. The explicit slot(name) syntax remains open-ended. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class SlotNames(vararg val value: String)

/** Compiler/editor contract for inline slot declarations and generated slots.name functions. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class SlotDeclaration(val name: String = "")

/** Compiler/editor contract on generated DSL parameters. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class VBind(val prop: String, val event: String, val variadic: Boolean = false)

/** Required props may be supplied by a binding instead of a direct argument. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class RequiredBindingProp
