import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import '@fontsource/funnel-sans/400.css'
import '@fontsource/funnel-sans/500.css'
import '@fontsource/funnel-sans/600.css'
import '@fontsource/funnel-display/600.css'
import '@fontsource/geist-mono/400.css'
import '@fontsource/geist-mono/500.css'
import './styles/theme.css'
import App from './App.vue'
const routes = [
  { path: '/', component: () => import('./views/Dashboard.vue'), meta: { title: '经营总览' } },
  { path: '/shops', component: () => import('./views/Shops.vue'), meta: { title: '店铺管理' } },
  { path: '/listings', component: () => import('./views/Listings.vue'), meta: { title: 'SKU 列表' } },
  { path: '/anomalies', component: () => import('./views/Listings.vue'), props: { anomalyOnly: true }, meta: { title: '异常商品' } },
  { path: '/strategies', component: () => import('./views/Strategies.vue'), meta: { title: '规则配置' } },
  { path: '/tasks', component: () => import('./views/Tasks.vue'), meta: { title: '任务记录 / 批量调价' } },
  { path: '/history', component: () => import('./views/History.vue'), meta: { title: '价格历史' } },
  { path: '/logs', component: () => import('./views/Logs.vue'), meta: { title: '接口与审计日志' } }
]
createApp(App).use(createRouter({ history: createWebHashHistory(), routes })).use(ElementPlus, { locale: zhCn }).mount('#app')
