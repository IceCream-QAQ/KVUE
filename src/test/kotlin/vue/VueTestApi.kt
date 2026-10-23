@file:JsModule("vue")
@file:JsNonModule

package vue

import kotlin.js.Promise

external fun nextTick(): Promise<Unit>
