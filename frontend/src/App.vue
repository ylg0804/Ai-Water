<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Activity, Bell, ChevronRight, LayoutDashboard, LogOut, Menu, Radio, Waves, Search, Maximize, UserRound } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { platform } from './services/platform/store'
import { session } from './services/session'
import { deviceDisplayName, deviceSecondaryLabel } from './domain/device'

const route = useRoute()
const router = useRouter()
const menuOpen = ref(false)
const collapsed = ref(false)
const searchText = ref('')
const searchOpen = ref(false)
const nav = [
  { path: '/dashboard', title: '数据看板', icon: LayoutDashboard },
  { path: '/history', title: '历史数据', icon: Activity },
  { path: '/devices', title: '设备管理', icon: Radio },
  { path: '/alarms', title: '告警中心', icon: Bell },
]
const openCount = computed(() => platform.state.alarms.filter(alarm => alarm.status === 'OPEN').length)
const currentDate = ref(new Date())
const todayFormatter = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long', timeZone: 'Asia/Shanghai' })
const today = computed(() => todayFormatter.format(currentDate.value))
const dateTimer = setInterval(() => { currentDate.value = new Date() }, 60_000)
const searchResults = computed(() => {
  const text = searchText.value.trim().toLowerCase()
  const pages = nav.filter(item => !text || item.title.includes(text)).map(item => ({ name: item.title, hint: '功能页面', path: item.path }))
  const devices = text ? platform.state.devices.filter(device => `${device.name} ${device.serial}`.toLowerCase().includes(text)).slice(0, 5).map(device => ({ name: deviceDisplayName(device), hint: deviceSecondaryLabel(device), path: `/devices?search=${encodeURIComponent(device.serial)}` })) : []
  return [...pages, ...devices]
})
watch(() => route.fullPath, () => { menuOpen.value = false; searchOpen.value = false })
watch(() => session.state.user?.username, username => {
  if (username) platform.start()
  else platform.stop()
}, { immediate: true })
onBeforeUnmount(() => { clearInterval(dateTimer); platform.stop() })
function logout() { session.logout(); void router.replace('/login') }
function toggleSidebar() { if (window.matchMedia('(max-width: 850px)').matches) menuOpen.value = !menuOpen.value; else collapsed.value = !collapsed.value }
function goToResult(path?: string) {
  if (!path) return
  const target = path.startsWith('/devices?search=') ? `${path}&request=${crypto.randomUUID()}` : path
  void router.push(target)
  searchOpen.value = false
  searchText.value = ''
}
async function toggleFullscreen() {
  try { if (document.fullscreenElement) await document.exitFullscreen(); else await document.documentElement.requestFullscreen() }
  catch { ElMessage.info('当前浏览器不支持页面全屏。') }
}
</script>

<template>
  <RouterView v-if="route.path === '/login'" />
  <div v-else class="app-shell" :class="{ 'sidebar-collapsed': collapsed }">
    <button v-if="menuOpen" class="sidebar-scrim" aria-label="关闭导航菜单" @click="menuOpen = false" />
    <aside class="sidebar" :class="{ 'is-open': menuOpen }">
      <RouterLink class="brand" to="/dashboard" aria-label="平台首页">
        <span class="brand-mark"><Waves :size="25" :stroke-width="2" /></span>
        <span class="brand-copy"><strong>灌区水情监测</strong></span>
      </RouterLink>
      <div class="workspace-label">功能菜单</div>
      <nav class="primary-nav" aria-label="主导航">
        <RouterLink v-for="item in nav" :key="item.path" :to="item.path" class="nav-link" :aria-label="item.title" :title="item.title">
          <component :is="item.icon" :size="19" :stroke-width="1.8" /><span>{{ item.title }}</span>
          <span v-if="item.path === '/alarms' && openCount" class="nav-count">{{ openCount }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar-bottom">
        <div class="user-profile">
          <span class="avatar"><UserRound :size="18" /></span><span><strong>{{ session.state.user?.username ?? '用户' }}</strong></span>
          <button class="icon-button" title="退出登录" aria-label="退出登录" @click="logout"><LogOut :size="17" /></button>
        </div>
      </div>
    </aside>

    <div class="app-body">
      <header class="topbar">
        <div class="topbar-start">
          <button class="icon-button sidebar-toggle" aria-label="展开或收起导航" @click="toggleSidebar"><Menu :size="21" /></button>
          <div class="global-search" @focusout="event => { if (!(event.currentTarget as HTMLElement).contains(event.relatedTarget as Node)) searchOpen = false }">
            <Search :size="17" /><input v-model="searchText" placeholder="搜索页面或监测设备" aria-label="搜索页面或监测设备" @focus="searchOpen = true" @keydown.escape="searchOpen = false" @keydown.enter.prevent="goToResult(searchResults[0]?.path)" />
            <div v-if="searchOpen" class="search-results"><span class="search-caption">{{ searchText ? '搜索结果' : '快速前往' }}</span><button v-for="result in searchResults" :key="result.path" @click="goToResult(result.path)"><span>{{ result.name }}<small v-if="result.hint">{{ result.hint }}</small></span><ChevronRight :size="14" /></button><p v-if="!searchResults.length">没有找到相关页面或设备</p></div>
          </div>
        </div>
        <div class="topbar-actions"><span class="topbar-date">{{ today }}</span><button class="icon-button fullscreen-button" aria-label="切换页面全屏" @click="toggleFullscreen"><Maximize :size="19" /></button><button class="icon-button notification-button" aria-label="查看未处理告警" @click="router.push('/alarms?status=OPEN')"><Bell :size="19" /><span v-if="openCount" class="notification-count">{{ openCount }}</span></button><el-dropdown trigger="click" @command="logout"><button class="header-user"><span><UserRound :size="19" /></span>{{ session.state.user?.username ?? '用户' }}</button><template #dropdown><el-dropdown-menu><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div>
      </header>
      <main class="main-content">
        <el-alert v-if="platform.state.storageWarning" :title="platform.state.storageWarning" type="warning" :closable="false" show-icon class="storage-notice" />
        <RouterView />
      </main>
    </div>
  </div>
</template>
