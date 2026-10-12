import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'

const app = createApp(App)

// 独立运行模式：v-hasPermi / v-hasRole 直接放行（生产环境可替换为真实权限校验）
app.directive('hasPermi', { mounted: () => {}, updated: () => {} })
app.directive('hasRole', { mounted: () => {}, updated: () => {} })

app.use(createPinia())
app.use(ElementPlus)
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}
app.mount('#app')
