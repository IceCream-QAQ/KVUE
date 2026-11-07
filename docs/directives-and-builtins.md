# 指令与 Vue 内置组件

这些接口直接使用项目依赖的 Vue 3.5.44。导入 `kvue.*`、`kvue.vdom.*` 以及需要的 `kvui.*` 即可使用。
内置组件与原生元素一样返回节点，内容仍在 Vue 请求时执行；无需声明 `@JsComponent` 或配置第三方导入。

## 单根与 Fragment

组件的 `body` 只有一个节点时，KVUE 直接返回它；没有节点时返回注释，多根时返回 Fragment。
这让 `v-show`、自定义指令和 TransitionGroup 的移动动画能够找到单根 Kotlin 组件的真实元素。
单根 attrs 透传及 `update:prop` 监听器过滤继续保留。

根节点数量变化时，Vue 可能因根类型变化而重新挂载子树。需要在增减同级节点时保留组件实例，显式包一层 Fragment：

```kotlin
override val body = RenderFun {
    Fragment {
        if (showExtra) ExtraPanel(key = "extra")
        Editor(key = "editor")
    }
}
```

Fragment 不增加 DOM 元素，可以设置 `key`，也可以用于列表内的成组节点。指令和动画仍应作用于其中的具体元素或单根组件。
这替代了早期版本对所有组件隐式包裹 Fragment 的行为。已有代码若依赖多根/单根切换时保留子组件，应按上例显式分组。

## 显示隐藏与自定义指令

```kotlin
// show 使用 Vue v-show：隐藏时保留 DOM、组件状态和实例引用。
Counter().show(visible).onChanged { count = it }.ref(counter)
```

`show(false)` 设置 `display: none`，再次显示时恢复原来的 display。多根组件应将它放到内部具体元素。

自定义指令在组件初始化或模块级创建，避免每次渲染创建新的定义：

```kotlin
private val labelDirective = directive<String> {
    mounted = { element, binding, _, _ ->
        element.setAttribute("aria-label", binding.value)
    }
    updated = { element, binding, _, _ ->
        element.setAttribute("aria-label", binding.value)
    }
}

override val body = RenderFun {
    button { text("保存") }
        .directive(labelDirective, label, argument = "save", modifiers = mapOf("exact" to true))
        .show(visible)
}
```

支持 `created`、`beforeMount`、`mounted`、`beforeUpdate`、`updated`、`beforeUnmount`、`unmounted` 和 `deep`。
回调提供 DOM `Element`、类型化 `DirectiveBinding<T>`、当前 VNode 和可空的前一个 VNode。
`binding` 包含 `value`、`oldValue`、`argument`、`modifiers` 和 `instance`；首次没有旧值时 `oldValue` 为 null。
不需要值的指令可以使用 `directive<Unit> { ... }`，通过 `.directive(focusDirective)` 应用。

`.directive(...)` 和 `.show(...)` 都保留生成节点类型，可继续调用组件事件、样式和实例引用方法。
全局注册使用 `app.directive("label", labelDirective)`；与 JS 互操作时使用 `asVueDirective()`。原有接受原生 `vue.Directive` 的节点方法继续可用。

## Transition 与 TransitionGroup

```kotlin
Transition(name = "fade", mode = TransitionMode.OutIn) {
    if (visible) Counter(key = "counter")
}

TransitionGroup(name = "list", tag = "ul", moveClass = "list-move") {
    rows.forEach { row ->
        RowItem(row = row, key = row.id)
    }
}
```

Transition 的内容应是一个元素或单根组件，TransitionGroup 的每个列表节点应有稳定且唯一的 key。
CSS 仍由应用样式表提供。支持 `appear`、`css`、`type`、`TransitionDuration(enter, leave)`、`TransitionClasses` 和 `TransitionHooks`。
Transition 另有 `mode` 和 `persisted`；TransitionGroup 另有 `tag`、`moveClass`，不提供不适用的 mode。

```kotlin
Transition(
    css = false,
    hooks = TransitionHooks(
        onEnter = { element, done ->
            // 在动画完成后调用 done()。
            done()
        },
        onLeave = { element, done -> done() },
    ),
) { Counter() }
```

钩子覆盖 enter / leave / appear 的 before、执行、after 和 cancelled 阶段；带 `done` 的回调必须在动画完成时调用它。
配合 `show` 时，像 Vue 渲染函数一样显式设置 `persisted = true`：

```kotlin
Transition(name = "fade", persisted = true) {
    Counter().show(visible)
}
```

## KeepAlive 与动态组件

```kotlin
// 组件定义是稳定对象；切换它们使用 shallowRef，避免把定义本身深度代理。
private var active by shallowRef<VueComponentType>(EditorVue.definition)

override val body = RenderFun {
    KeepAlive(
        include = KeepAliveFilter.names("Editor", "Preview"),
        max = 5,
    ) {
        component(active)
    }
}
```

`include` / `exclude` 支持 `KeepAliveFilter.names(...)`、原生 JS 正则语法的 `KeepAliveFilter.pattern("^Editor", "i")`，
以及 `KeepAliveFilter.anyOf(...)` 的组合。匹配的是 Vue 组件 name；`max` 为正整数，沿用 Vue 的 LRU 淘汰规则。
生命周期使用现有的 `onActivated` / `onDeactivated`。

`component(definition, props = mapOf(...), key = ...)` 可以渲染 Kotlin、导入或异步组件；传入的 null prop 会保留。
`component("RegisteredName")` 在当前渲染上下文中解析已注册组件名，也可以渲染原生标签。
内容块支持默认内容与显式 `slot("name") { ... }`，规则与普通组件一致。

## Teleport

```kotlin
Teleport(to = "#dialogs", disabled = inline, defer = true) {
    DialogContent()
}
```

`to` 接受 CSS 选择器或 DOM `Element`；切换 `disabled` 时保留子节点身份。
`defer` 用于同一次挂载/更新中稍后出现的目标元素，不等待任意将来时刻。所有内容在卸载时由 Vue 清理。

## Suspense 与异步组件

```kotlin
// loader 返回 Promise<VueComponentType>，定义在渲染之外创建一次。
private val asyncPanel = defineAsyncComponent(
    AsyncComponentOptions(delay = 200, timeout = 10_000),
) {
    loadPanelDefinition()
}

override val body = RenderFun {
    Suspense(timeout = 0, onResolve = { println("已加载") }) {
        component(asyncPanel)
        slots.fallback { p { text("正在加载…") } }
    }
}
```

也可以用 `slots.default { ... }`，但不要与普通默认内容混用；default/fallback 各提供一个直接子节点。
Suspense 支持 `timeout`、`suspensible`、`onPending`、`onResolve`、`onFallback`，沿用 Vue 的实验性组件行为。
它处理 Vue 的异步组件依赖，不会自动等待 Kotlin 组件中任意启动的异步请求。

`AsyncComponentOptions` 提供 `loadingComponent`、`errorComponent`、`delay`、`timeout`、`suspensible` 和
`onError(error, retry, fail, attempts)`。在 Suspense 管理下，加载状态由 Suspense 决定；设置 `suspensible = false` 可使用组件自己的加载/错误状态。
原生 `vue.defineAsyncComponent` 仍用于接收任意 JS loader/options。

## 验证

运行 `./gradlew.bat :kvue-runtime:jsBrowserTest :kvui:jsBrowserTest`。
运行库浏览器用例覆盖指令完整生命周期、deep、显隐与实例保持、Transition 钩子及取消、TransitionGroup 真实布局移动、
Fragment 子树稳定、Teleport 延迟目标和禁用切换、KeepAlive 状态/过滤/LRU、Suspense 插槽事件、异步重试和动态组件。

语义参考：[Vue 自定义指令](https://vuejs.org/guide/reusability/custom-directives.html)、[内置组件](https://vuejs.org/api/built-in-components.html)、
[异步组件](https://vuejs.org/guide/components/async.html)。
