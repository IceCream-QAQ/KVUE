# 插槽

组件内容块可以同时提供默认内容和具名插槽：

```kotlin
AComponent {
    slot("header") {
        text("头部内容")
    }
    text("默认插槽内容")
    slot("footer") {
        text("底部内容")
    }
}
```

这里带有内容块的 `slot(name) { ... }` 向子组件提供插槽。子组件在自己的 `body` 中通过 `slot("header")`、`slot()` 和 `slot("footer")` 决定渲染位置。

## 具名简写与名称冲突

声明了插槽名称的组件会生成专属的 `slots` 命名空间：

```kotlin
AComponent {
    slots.header {
        text("头部内容")
    }
    text("默认插槽内容")
    slots.footer {
        text("底部内容")
    }
}
```

`slots.header {}` 表示插槽，`kvui.header {}` 或导入后的 `header {}` 仍然表示 HTML 元素。不会生成裸露的 `header`、`footer` 插槽函数，因此它们不会遮蔽同名组件或 DOM 函数。

原生 Kotlin 组件通过 `@SlotNames` 声明名称：

```kotlin
@VueComponent
@SlotNames("header", "footer")
class AComponent : Component() {
    override val body = RenderFun {
        div {
            slot("header")
            slot()
            slot("footer")
        }
    }
}
```

名称可以从基类继承。`@SlotNames` 用于生成命名成员，不限制显式 `slot("任意名称")` 的使用。

第三方组件从 TypeScript 的 `$slots` 声明读取名称，并由 KSP 和 IDEA 生成相同的成员。读取不到名称时仍可使用显式写法。非普通标识符会转换为 Kotlin 成员名，例如 `empty-state` 对应 `slots.emptyState`；转换重名时添加 `Slot` 后缀。

## 延迟执行与声明位置

内嵌插槽依赖 KVUE 编译器插件。编译器将顶层具名插槽声明与默认内容分开：建立组件节点时登记插槽，其渲染代码仍在子组件调用相应插槽时才执行。普通语句和局部变量留在默认插槽中；子组件没有调用默认插槽时，这些语句也不会执行。

当前要求：

- 内嵌的 `slot(...) {}`、`slots.xxx {}` 应直接放在组件内容块顶层。
- 具名插槽不能引用在该组件内容块中声明的默认插槽局部变量。共享值应声明在组件调用之前，或者分别放入各插槽内部。
- 显式的 `slot("default") {}` 与普通默认内容不能混用。

编译期和 IDEA 都会检查不合法的声明位置及局部变量引用。例如共享值应这样放置：

```kotlin
val title = "标题"
AComponent {
    slots.header { text(title) }
    text(title)
}
```

插槽内部可以正常使用条件、循环、嵌套组件和双向绑定。若需要按条件决定是否提供整个插槽，可以配置返回的节点：

```kotlin
AComponent {
    text("默认内容")
}.apply {
    if (showHeader) {
        slot("header") { text("可选头部") }
    }
}
```

## 节点配置与复用

原有节点注册方式继续可用，并保留具体节点类型：

```kotlin
AComponent()
    .slot("header") { text("头部") }
    .slot("footer") { text("底部") }
    .style { this["padding"] = "12px" }
```

同名插槽多次注册时，内容按注册顺序拼接。拥有对应事件的组件可以继续链式注册事件。

组件尾随块的类型现在是 `ComponentContent<AComponentVue.Content>`，其接收者继承 `RenderGroup`，并提供该组件的 `slots`。辅助类型放在 `AComponentVue` 对象内部，避免与组件库中已有的 `XxxContent` 等组件同名。已有的独立 `RenderFun` 可以通过 `kvue.vdom.asComponentContent` 适配：

```kotlin
val reusable = RenderFun { text("复用内容") }
AComponent(content = reusable.asComponentContent())
```

更新这一功能时，运行库、KSP、编译器插件和 IDEA 插件需要同步更新，并重新生成旧组件接口。

插槽回调目前仍不接收作用域参数；原生 Kotlin 插槽出口的兜底内容也仍需要另行扩展。
