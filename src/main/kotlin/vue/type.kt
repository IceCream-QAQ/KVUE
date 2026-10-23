package vue

typealias VueRenderFun = () -> Array<VNode>
typealias VueSetupFun = (dynamic, dynamic) -> VueRenderFun

