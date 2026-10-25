# KVUE 运行时与 Vue API

运行时基于 Vue 3.5.44。API 分成两个入口：

| 入口 | 用途 |
| --- | --- |
| `kvue.*` | Kotlin 委托、监听回调、生命周期、类型化注入和 effect scope |
| `vue.*` | 直接调用 Vue 的原生 JS 导出；组件 options、slots、attrs 等复杂对象保留 JS 形态 |

推荐在组件中使用 `kvue` 入口，第三方 JS 互操作按需使用 `vue`。这些都是普通 Kotlin API，IDE 通过库声明提供提示。

## 链式事件、样式与组件实例

组件 DSL 返回生成的专属节点，例如 `CounterNode`。事件方法来自 `@Emit` 或导入的 emits 声明，参数类型与原有函数参数一致：

```kotlin
import kvue.vdom.knode.css
import kvue.vdom.knode.style

Counter(onChanged = { value -> println(value) })
    .css { +"counter"; add("active", enabled) }
    .style { this["color"] = "red"; this["marginTop"] = "8px" }
    .onChanged { value -> save(value) }
```

`css` 配置 class 名称，`style` 合并内联 CSS；它们保留具体节点类型，不生成样式表、选择器或伪类规则。
参数监听器在前，链式监听器按调用顺序追加。同名事件不会互相覆盖，也不会覆盖 `vModel` 的回写。
每次 render 都构造新的监听配置，Vue 更新对应 VNode，不会跨 render 累积订阅。
KVUE 保持稳定的 Fragment 根结构，并将 attrs 合并到唯一的元素/组件根节点；多根组件应显式选择属性落点。

在 Kotlin 组件上用 `@Expose` 声明公开接口：

```kotlin
@Expose
fun reset(value: Int): Int {
    count = value
    return count
}

@Expose
var count by ref(0)
```

生成器创建 `CounterInstance` facade，并用绑定到当前 Kotlin 实例的函数和属性访问器接入 Vue `expose()`。
方法可通过 `@Expose(name = "js-name")` 指定 JS 名称。只导出显式标记的成员。
目前方法要求可访问、非泛型、非 suspend、无扩展接收者、无默认参数和 vararg；复杂入口可提供一个明确签名的包装方法。
属性可为 val 或 var，var 的 setter 也必须可访问；`@Expose` 不能与 `@Prop` / `@Emit` 混用。

```kotlin
// 父组件成员初始化时创建一次；不要放在每轮 render 内创建。
private val child = CounterVue.ref() // ComponentRef<CounterInstance>
private val instance by child       // 可选的只读委托

override val body = RenderFun {
    Counter().ref(child).onChanged { /* ... */ }
    button(onClick = { child.value?.reset(0) }) { text("重置") }
}
```

`ComponentRef` 是只读、浅响应式句柄，可以使用 `watch(child)`。
挂载前/卸载后为 null，普通更新保留 facade 身份，重新挂载创建新 facade。
同一个 ref 绑定一个组件位置；列表中使用按 key 保存的独立 ref。异步组件的引用需要等实际挂载后才可用。
它持有 Vue 公开代理，`asDynamic()` 提供显式 JS 互操作入口；不会暴露 Vue 内部 instance。
函数式 JS 组件没有组件实例，生成器不为它提供 `ref()`。

## ref 与 computed

```kotlin
import kvue.*

val counter = ref(0)
var count by counter
val doubled by computed { count * 2 }
var editable by computed(get = { count * 2 }, set = { count = it / 2 })

counter.value = 2       // count 同步变为 2
editable = 10          // 调用 setter，count 变为 5
```

`RefValue<T>` 同时支持 `.value` 和 `by`，可用于组件成员、普通类、顶层属性和局部变量。
`ReadRef<T>` 只提供读取接口；只读 computed 返回 `ReadonlyRef<T>`，因此只能委托 `val`。
`Component` 保留 `ref(...)` 成员入口，并提供 `shallowRef(...)` 和 `computed(...)` 的同名便利方法。

还提供 `shallowRef`、`triggerRef`、`customRef`、`reactive`、`readonly`、`shallowReactive`、`shallowReadonly`、
`isRef`、`isReactive`、`isReadonly`、`isShallow`、`isProxy`、`toRaw`、`markRaw`、`unref`、`toValue`、`toRef` 和 `toRefs`。

```kotlin
val writable = toRef(state::count)   // 可变 Kotlin 属性，使用真实 getter/setter
val readable = toRef { state.count + 1 }
writable.value = 3

val delayed = customRef<Int> { track, trigger ->
    var stored = 0
    RefAccessor(
        get = { track(); stored },
        set = { stored = it; trigger() },
    )
}
```

传给第三方 JS 库时，通过 `counter.asVueRef()` 取得原生 ref；Kotlin 的 ref 包装对象本身不是 JS ref。
包装对象标记为 raw，放进响应式对象时不会被 Vue 代理后错误展开其内部字段。

## 如何监听 by ref

```kotlin
@VueComponent
class Counter : Component() {
    private var count by ref(0)
    private val doubled by computed { count * 2 }

    init {
        watch(::count) { value, old ->
            println("$old -> $value")
        }
        watch({ doubled }, WatchOptions(immediate = true)) { value, old ->
            if (isInitial) println("初始值：$value")
            onCleanup { /* 取消上一次请求或释放其资源 */ }
        }
        watchEffect {
            println(count)
            onCleanup { /* 本轮副作用的清理 */ }
        }
    }

    override val body = RenderFun { /* ... */ }
}
```

上例还需导入 `kvue.annotation.VueComponent`、`kvue.vdom.RenderFun` 和使用到的 `kvue.*` 函数。

`watch` 接受 getter、绑定到对象的属性引用、Kotlin ref 句柄或原生 `vue.Ref<T>`。
委托属性通过 getter 读取底层 ref，由 Vue 收集依赖，不读取 `$delegate`，也不需要编译器改写。
不接受 `watch(count)` 这种普通值；它已经是取出的快照。局部变量可写 `watch({ count })`；Kotlin 不支持局部变量的 `::count` 引用。
监听只读属性也有效，不要求 setter。监听和双向绑定的可写性要求不同。

回调类型是 `WatchContext.(T, T?) -> Any?`：

- `oldValue` 在首次 `immediate` 回调中为 null；`isInitial` 区分首次执行与真实旧值为 null 的情况。
- `onCleanup { ... }` 绑定到这一轮 watcher，可用于取消异步工作。回调最后返回的 JS Promise 会保留给 Vue 处理。
- `onWatcherCleanup { ... }` 是依赖当前 watcher 的全局入口，必须在同步执行阶段注册；可通过 `failSilently = true` 忽略没有活动 watcher 的情况。

```kotlin
val handle = watch(counter, WatchOptions(flush = WatchFlush.Sync)) { value, _ ->
    println(value)
}
handle.pause()
handle.resume()
handle.stop()  // 也可写 handle()

watchMany({ state.count }, { state.name }) { values, oldValues ->
    // 多源类型简化为 List<Any?>；旧值首次执行时为 null。
}
```

`watchMany` 将 getter 数组交给 Vue，保持原生多源比较和批处理行为。
需要保留 `shallowRef` + `triggerRef` 的强制通知行为时，直接 `watch(ref句柄)`；监听 getter 使用 getter 自己的比较规则。

`WatchOptions` 提供 `immediate`、`once`、`flush`、`deep`、`depth`、`onTrack`、`onTrigger`。
`deep` 与数字深度 `depth` 二选一；默认均不设置，保留 Vue 的默认行为。
`watchReactive(state)` 专门监听响应式对象，避免普通值误入 watch 重载。深层原地修改时新旧对象可能是同一个引用，不会自动生成历史快照。
`WatchFlush` 包括 `Pre`、`Post`、`Sync`；另提供 `watchPostEffect`、`watchSyncEffect`。
调度、清理和自动停止行为依据 [Vue watch / watchEffect](https://vuejs.org/api/reactivity-core.html#watch)。

## 生命周期、注入和作用域

KVUE 在 Vue 的真实 `setup` 中构造 class，因此可在属性初始化或 `init` 中注册钩子和 watcher：

```kotlin
val counterKey = injectionKey<ReadRef<Int>>("counter")  // ReadRef 位于 kvue.reactive

// 父组件 init
provide(counterKey, counter)
onMounted { /* DOM 已挂载 */ }
onUnmounted { /* 组件已卸载 */ }

// 子组件成员
private val count by requireInject(counterKey)
private val inputElement by useTemplateRef<Any>("input")
```

模板引用配合 `ElementNode.property("ref", "input")`，或 KVUI 的 `attributes = mapOf("ref" to "input")`。
引用挂载后可读，卸载后变为 null。

提供 before-mount / mounted、before-update / updated、before-unmount / unmounted、activated / deactivated、
error-captured、render-tracked / render-triggered 和 server-prefetch 钩子。
`onErrorCaptured` 返回 false 可阻止继续传播。
这些钩子按 [Vue 生命周期规则](https://vuejs.org/api/composition-api-lifecycle.html) 注册，浏览器测试不涵盖服务端执行。

`injectionKey<T>()` 生成真正的 JS Symbol，类型用于关联提供值与读取值。
`inject(key)` 返回可空结果，缺失时不警告；`inject(key, default)`、`injectFactory(key) { ... }` 支持默认值；
`requireInject(key)` 在缺失时抛错。提供的 null 是有效值，不会触发默认工厂。
类型化 key 也可直接传给 `vue.App.provide`，并通过 `app.runWithContext { ... }` 在应用上下文中读取。
原生层保留 [Vue provide / inject](https://vuejs.org/api/composition-api-dependency-injection.html) 的字符串 key 接口。

```kotlin
val scope = effectScope()
scope.run {
    watchEffect { println(counter.value) }
    onScopeDispose { /* 释放资源 */ }
}
scope.pause()
scope.resume()
scope.stop()
```

`getCurrentScope()` 可读取活动作用域；已停止 scope 的 `run` 返回 null。
在组件 setup 中同步创建的 watcher 由 Vue 随组件卸载自动停止；组件之外创建的 watcher 或 detached scope 应显式停止。
见 [Vue effectScope](https://vuejs.org/api/reactivity-advanced.html#effectscope)。

## 原生导出与类型边界

`vue` 包还导出类型化 `App` / `AppConfig`、`createApp`、`createSSRApp`、`nextTick`、`defineComponent`、`defineAsyncComponent`，
`h`、VNode 创建/克隆/判断、props 合并、组件/指令解析、事件修饰符，以及 Fragment、Teleport、Suspense、KeepAlive、Transition 等内置类型。
`getCurrentInstance`、`useAttrs`、`useSlots` 保留动态对象；Kotlin 层另包装 `useId` 和只读 `useTemplateRef`。
原生 `useModel` 保留未细分的 ref 类型，不模拟 TS 的修饰符元组与映射泛型。

有意简化的类型包括：

- 不复制 `UnwrapRef`、`DeepReadonly`、`DefineComponent` 的条件类型和映射类型。普通对象代理返回原始静态类型 `T`；它不代表任意嵌套 JS ref 解包都能保持 Kotlin 字段类型。
- 普通对象的 `readonly` 依靠 Vue 在运行时保护，Kotlin 的 `var` 声明不会自动变成 `val`；ref 包装层则有独立只读类型。
- `toRefs` 返回 `Map<String, RefValue<Any?>>`。`toRef<T>(object, "key")` 使用真实 JS 属性名；Kotlin 属性优先使用 `toRef(object::property)`，不猜测编译后的字段名。
- Kotlin 集合不是原生 JS Map/Set 的类型别名；需要原生对象/数组接口时应显式建模和转换。
- options、slots、attrs 和异步组件 loader 等边界使用 `dynamic`，调用方负责构造正确的 JS 对象。
- SFC 的 `defineProps` / `defineEmits` / `defineModel` 等编译宏、Vue 内部编译器辅助函数不作为 Kotlin 运行时 API 模拟。

`createApp(ComponentVue.definition)` 是 Kotlin 层便利入口；已有的 `vue.createApp(ComponentVue.definition.vue)` 仍可使用。

浏览器验证随本模块运行：`gradlew :kvue-runtime:jsBrowserTest`。测试覆盖委托与句柄、computed、watch 清理和调度、深度、
effect scope、真实 class 组件的生命周期/注入/模板引用、卸载自动停止及原生渲染入口。
