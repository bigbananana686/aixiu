<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watchEffect } from 'vue'
import Brand from './Brand.vue'
import Dashboard from './Dashboard.vue'
import Exceptions from './Exceptions.vue'
import Feedback from './Feedback.vue'
import Icon from './Icon.vue'
import Login from './Login.vue'
import Orders from './Orders.vue'
import Resources from './Resources.vue'
import LiveResources from './LiveResources.vue'
import Knowledge from './Knowledge.vue'
import Users from './Users.vue'
import { orders, exceptions } from './orders.js'
import { api, demo, listAll, readSession, storeSession } from './api.js'
import { exceptionRow, orderRow } from './live-orders.js'

const groups = [
  { label: '总览', items: [{ key: 'dashboard', title: '运营工作台', icon: 'home' }] },
  { label: '服务运营', items: [{ key: 'orders', title: '工单中心', icon: 'file' }] },
  { label: '售后与异常', items: [
    { key: 'exceptions', title: '服务异常', icon: 'shield', description: '核查延期时段冲突与超计划完工。' },
    { key: 'feedback', title: '问题反馈', icon: 'alert', description: '查看车主提交的问题反馈和服务投诉。' },
  ] },
  { label: '资源管理', items: [
    { key: 'catalog', title: '服务目录', icon: 'wrench', description: '维护车主可预约的保养项目、参考价格和服务时长。' },
    { key: 'schedule', title: '技师排班', icon: 'calendar', description: '查看并调整技师可接上门任务的工作窗口。' },
    ...(!demo ? [
      { key: 'knowledge', title: '知识资料', icon: 'file', description: '上传养护资料并查看索引和发布进度。' },
      { key: 'users', title: '用户管理', icon: 'shield', description: '查询账号、调整车主和技师角色、启用或停用账号。' },
    ] : []),
  ] },
]
const sections = Object.fromEntries(groups.flatMap(group =>
  group.items.map(item => [item.key, { ...item, group: group.label }]),
))
const sessionKey = 'aixiu-admin-preview'
const account = ref(demo ? (sessionStorage.getItem(sessionKey) === 'demo-admin' ? 'demo-admin' : '') : (readSession()?.loginName || ''))
const section = ref('dashboard')
const current = computed(() => sections[section.value])
const pageTitle = ref(null)
const ordersView = ref(null)
const feedbackView = ref(null)
const exceptionType = ref('')
const exceptionQuery = ref('')
const orderRows = ref(demo ? orders.slice() : [])
const exceptionRows = ref(demo ? exceptions : [])
const technicians = ref({})
const loading = ref(false)
const loadError = ref('')
const updatedAt = ref(new Date())
const refreshNotice = ref('')
const timestampFormat = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'short',
})

const compact = window.matchMedia('(max-width: 840px)')
const railOpen = ref(!compact.matches)
function syncRail(event) { railOpen.value = !event.matches }
compact.addEventListener('change', syncRail)
onUnmounted(() => compact.removeEventListener('change', syncRail))

async function loadOrders() {
  if (loading.value || !account.value) return
  const activeAccount = account.value
  loading.value = true
  loadError.value = ''
  try {
    if (demo) {
      orderRows.value = orders.slice()
    } else {
      const [orders, people, exceptions] = await Promise.all([
        listAll('/admin/orders'), listAll('/admin/technicians'), listAll('/admin/order-exceptions'),
      ])
      if (account.value !== activeAccount) return
      technicians.value = Object.fromEntries(people.map(item => [item.technicianId, item.displayName]))
      orderRows.value = orders.map(item => orderRow(item, technicians.value))
      exceptionRows.value = exceptions.map(item => exceptionRow(item, technicians.value))
    }
    updatedAt.value = new Date()
  } catch (error) {
    if (account.value === activeAccount) loadError.value = error.message
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (!demo) window.addEventListener('aixiu-session-expired', logout)
  loadOrders()
})
onUnmounted(() => window.removeEventListener('aixiu-session-expired', logout))

watchEffect(() => {
  document.title = (account.value ? current.value.title : '登录管理工作区') + ' | 爱修'
})

function navigate(key) {
  section.value = key
  refreshNotice.value = ''
  if (compact.matches) railOpen.value = false
  if (key === 'feedback' && demo) feedbackView.value.load()
  nextTick(() => pageTitle.value?.focus())
}

function login(name, session) {
  if (demo) sessionStorage.setItem(sessionKey, name)
  else storeSession(session)
  account.value = name
  navigate('dashboard')
  loadOrders()
}

function logout() {
  sessionStorage.removeItem(sessionKey)
  storeSession(null)
  account.value = ''
  section.value = 'dashboard'
  exceptionType.value = ''
  exceptionQuery.value = ''
  railOpen.value = !compact.matches
  if (!demo) {
    orderRows.value = []
    exceptionRows.value = []
  }
  nextTick(() => document.getElementById('login-title')?.focus())
}

async function refresh() {
  await loadOrders()
  if (!loadError.value) refreshNotice.value = '已重新载入工单。'
}

function filterOrders(status) {
  navigate('orders')
  ordersView.value.filterStatus(status)
}

function openOrder(id) {
  navigate('orders')
  ordersView.value.openLinkedOrder(id)
}

function filterExceptions(type = '', orderId = '') {
  exceptionType.value = type
  exceptionQuery.value = orderId
  navigate('exceptions')
}
</script>

<template>
  <Login v-if="!account" @login="login" />
  <div v-else class="workspace" :class="{ 'rail-open': railOpen }">
    <a class="skip" href="#content">跳到主要内容</a>
    <aside v-show="railOpen" id="workspace-menu" class="rail">
      <Brand />
      <nav class="rail-menu" aria-label="运营菜单">
        <div v-for="group in groups" :key="group.label" class="nav-group">
          <p>{{ group.label }}</p>
          <button v-for="item in group.items" :key="item.key" type="button"
            :aria-current="section === item.key ? 'page' : undefined" @click="navigate(item.key)">
            <Icon :name="item.icon" /> {{ item.title }}
          </button>
        </div>
      </nav>
      <div class="preview-note"><span></span> 爱修运营管理台</div>
    </aside>
    <button v-if="railOpen" class="rail-backdrop" type="button" aria-label="关闭运营菜单" @click="railOpen = false"></button>
    <div class="workspace-body">
      <header class="topbar">
        <button class="icon-button" type="button" aria-controls="workspace-menu" :aria-expanded="railOpen"
          :aria-label="railOpen ? '收起菜单' : '展开菜单'" @click="railOpen = !railOpen">
          <Icon name="menu" />
        </button>
        <nav class="breadcrumb" aria-label="当前位置">
          <span>工作空间</span><span aria-hidden="true">/</span><span aria-current="page">{{ current.title }}</span>
        </nav>
        <div class="account">
          <span v-if="demo" class="mode-label">本地预览</span>
          <span class="avatar" aria-hidden="true">{{ account[0].toUpperCase() }}</span>
          <span class="account-text"><strong>{{ account }}</strong><small>{{ demo ? '演示管理员' : '管理员' }}</small></span>
          <button class="icon-button" type="button" aria-label="退出登录" @click="logout"><Icon name="logout" /></button>
        </div>
      </header>
      <main id="content" class="workspace-main" tabindex="-1">
        <header class="heading">
          <div>
            <p class="section-group">{{ current.group }}</p>
            <h1 ref="pageTitle" tabindex="-1">{{ current.title }}</h1>
            <p v-if="current.description" class="section-description">{{ current.description }}</p>
          </div>
          <div v-if="section === 'dashboard' || section === 'orders'" class="heading-actions">
            <span :title="refreshNotice">载入于 <time :datetime="updatedAt.toISOString()">{{ timestampFormat.format(updatedAt) }}</time></span>
            <button class="quiet" type="button" :disabled="loading" @click="refresh"><Icon name="refresh" /> 刷新</button>
            <span class="sr-only" role="status">{{ refreshNotice }}</span>
          </div>
        </header>
        <p v-if="loading && ['dashboard', 'orders', 'exceptions'].includes(section)" class="empty" role="status">正在载入工单与异常…</p>
        <p v-if="loadError && ['dashboard', 'orders', 'exceptions'].includes(section)" class="empty form-error" role="alert">{{ loadError }}
          <button class="text-button" type="button" @click="refresh">重试</button>
        </p>
        <Dashboard v-show="section === 'dashboard'" :orders="orderRows" :exceptions="exceptionRows" @open-orders="filterOrders" @open-exceptions="filterExceptions" />
        <Orders v-show="section === 'orders'" ref="ordersView" :orders="orderRows" :exceptions="exceptionRows" :technicians="technicians" @open-exceptions="filterExceptions('', $event)" />
        <template v-if="demo">
          <Resources v-show="section === 'catalog'" section="catalog" />
          <Resources v-show="section === 'schedule'" section="schedule" />
        </template>
        <LiveResources v-else-if="section === 'catalog' || section === 'schedule'" :key="section" :section="section" />
        <Knowledge v-if="section === 'knowledge'" />
        <Users v-if="section === 'users'" />
        <Exceptions v-show="section === 'exceptions'" :items="exceptionRows" v-model:type="exceptionType" v-model:query="exceptionQuery" @open-order="openOrder" />
        <Feedback v-if="demo" v-show="section === 'feedback'" ref="feedbackView" :orders="orderRows" @open-order="openOrder" />
        <p v-else-if="section === 'feedback'" class="ledger empty">正式反馈接口待接入。本地演示版可预览反馈与投诉流程。</p>
      </main>
    </div>
  </div>
</template>
