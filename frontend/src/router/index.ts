import { createRouter, createWebHashHistory } from 'vue-router'
import { session } from '../services/session'

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/dashboard', component: () => import('../pages/DashboardPage.vue'), meta: { title: '数据看板' } },
    { path: '/history', component: () => import('../pages/HistoryPage.vue'), meta: { title: '历史数据' } },
    { path: '/devices', component: () => import('../pages/DevicesPage.vue'), meta: { title: '设备管理' } },
    { path: '/alarms', component: () => import('../pages/AlarmsPage.vue'), meta: { title: '告警中心' } },
    { path: '/login', component: () => import('../pages/LoginPage.vue'), meta: { title: '登录' } },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(to => {
  if (to.path !== '/login' && !session.state.user) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.path === '/login' && session.state.user) return '/dashboard'
})
router.afterEach(to => { document.title = `${String(to.meta.title ?? '监测平台')} · 灌区水情监测` })
