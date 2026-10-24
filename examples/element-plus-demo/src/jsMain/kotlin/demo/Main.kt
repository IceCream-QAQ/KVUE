package demo

import vue.createApp

fun main() {
    js("require('element-plus/dist/index.css')")
    createApp(PlaygroundVue.definition.vue).mount("#app")
}
