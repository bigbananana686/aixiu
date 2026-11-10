<script setup>
import { computed, onMounted, ref } from 'vue'
import { api, command, listAll } from './api.js'
import { localTime } from './live-orders.js'

const props = defineProps({ section: { type: String, required: true } })
const catalog = props.section === 'catalog'
const items = ref([])
const draft = ref(null)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const today = localTime(new Date()).slice(0, 10)
const rangeStart = ref(today)
const rangeEnd = ref(localTime(new Date(Date.now() + 7 * 86400000)).slice(0, 10))
const technician = ref('')
const schedule = ref(null)
const write = command()
const scheduleMatches = computed(() => schedule.value?.technicianId === technician.value
  && schedule.value.rangeStart === rangeStart.value && schedule.value.rangeEnd === rangeEnd.value)

async function run(work) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try { await work() } catch (problem) { error.value = problem.message }
  finally { busy.value = false }
}

async function load() {
  items.value = await listAll(catalog ? '/admin/service-items' : '/admin/technicians')
  if (!catalog && !technician.value) technician.value = items.value[0]?.technicianId || ''
}

function edit(item) {
  draft.value = { ...item }
  error.value = ''
  notice.value = ''
}

function saveItem() {
  return run(async () => {
    const { serviceItemId, code, name, unitPrice, durationMinutes, enabled, catalogVersion } = draft.value
    if (!/^\d{1,8}(\.\d{1,2})?$/.test(unitPrice)) throw new Error('请填写有效价格，最多两位小数。')
    const data = { name: name.trim(), unitPrice: Number(unitPrice).toFixed(2), durationMinutes, enabled }
    if (serviceItemId) {
      await write('/admin/service-items/' + serviceItemId, 'PATCH', { ...data, expectedCatalogVersion: catalogVersion })
    } else {
      await write('/admin/service-items', 'POST', { ...data, code: code.trim() })
    }
    draft.value = null
    await load()
    notice.value = '服务项目已保存。'
  })
}

function loadSchedule() {
  return run(async () => {
    const query = new URLSearchParams({ rangeStart: rangeStart.value, rangeEnd: rangeEnd.value })
    const value = await api('/admin/technicians/' + technician.value + '/windows?' + query)
    schedule.value = {
      ...value, windows: value.windows.map(window => ({
        start: localTime(window.windowStart).replace(' ', 'T'), end: localTime(window.windowEnd).replace(' ', 'T'),
      })),
    }
  })
}

function saveSchedule() {
  return run(async () => {
    if (!scheduleMatches.value) return
    const { technicianId, rangeStart, rangeEnd, scheduleVersion, windows } = schedule.value
    if (windows.some(window => !window.start || !window.end || window.start >= window.end)) {
      throw new Error('请为每个窗口填写时间，结束须晚于开始。')
    }
    const result = await write('/admin/technicians/' + technicianId + '/windows', 'PUT', {
      rangeStart, rangeEnd, expectedScheduleVersion: scheduleVersion,
      windows: windows.map(window => ({
        windowStart: new Date(window.start + '+08:00').toISOString(), windowEnd: new Date(window.end + '+08:00').toISOString(),
      })),
    })
    schedule.value.scheduleVersion = result.scheduleVersion
    notice.value = '工作窗口已保存。'
  })
}

onMounted(() => run(load))
</script>

<template>
  <section class="ledger">
    <p v-if="error" class="empty form-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="empty" role="status">{{ notice }}</p>
    <template v-if="catalog">
      <header class="panel-heading"><h2>服务项目</h2><button class="primary" type="button" :disabled="busy" @click="edit({ code: '', name: '', unitPrice: '0.00', durationMinutes: 30, enabled: true })">新增服务项目</button></header>
      <div class="table-scroll">
        <table>
          <thead><tr><th>编码</th><th>服务名称</th><th>参考价</th><th>时长</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in items" :key="item.serviceItemId">
              <td>{{ item.code }}</td><td>{{ item.name }}</td><td>¥{{ item.unitPrice }}</td><td>{{ item.durationMinutes }} 分钟</td>
              <td>{{ item.enabled ? '已启用' : '已停用' }}</td>
              <td><button class="text-button" type="button" :disabled="busy" :aria-label="'编辑' + item.name" @click="edit(item)">编辑</button></td>
            </tr>
            <tr v-if="!items.length"><td colspan="6" class="empty">{{ busy ? '正在读取…' : '暂无服务项目。' }}</td></tr>
          </tbody>
        </table>
      </div>
      <form v-if="draft" class="tools" @submit.prevent="saveItem">
        <label v-if="!draft.serviceItemId" class="search">项目编码<input v-model.trim="draft.code" required maxlength="32" placeholder="OIL_FILTER"></label>
        <label class="search">服务名称<input v-model.trim="draft.name" required maxlength="64"></label>
        <label class="search">参考价（元）<input v-model="draft.unitPrice" inputmode="decimal" required></label>
        <label class="search">服务时长（分钟）<input v-model.number="draft.durationMinutes" type="number" min="1" max="1440" required></label>
        <label><input v-model="draft.enabled" type="checkbox"> 已启用</label>
        <button class="primary" type="submit" :disabled="busy">保存</button>
        <button class="quiet" type="button" :disabled="busy" @click="draft = null">取消</button>
      </form>
      <footer><button class="text-button" type="button" :disabled="busy" @click="run(load)">刷新目录</button></footer>
    </template>
    <template v-else>
      <form class="tools" @submit.prevent="loadSchedule">
        <label class="search">技师<select v-model="technician" :disabled="busy" required>
          <option v-for="item in items" :key="item.technicianId" :value="item.technicianId">{{ item.displayName }}{{ item.enabled ? '' : '（停用）' }}</option>
        </select></label>
        <label class="search">开始日期<input v-model="rangeStart" :disabled="busy" type="date" required></label>
        <label class="search">结束日期（不含）<input v-model="rangeEnd" :disabled="busy" type="date" required></label>
        <button class="primary" type="submit" :disabled="busy || !technician">读取工作窗口</button>
      </form>
      <form v-if="scheduleMatches" @submit.prevent="saveSchedule">
        <header class="ledger-heading"><h2>工作窗口</h2><span>北京时间 · 版本 {{ schedule.scheduleVersion }}</span></header>
        <div v-for="(window, index) in schedule.windows" :key="index" class="tools">
          <label class="search">开始时间<input v-model="window.start" type="datetime-local" required></label>
          <label class="search">结束时间<input v-model="window.end" type="datetime-local" required></label>
          <button class="quiet" type="button" :disabled="busy" @click="schedule.windows.splice(index, 1)">移除</button>
        </div>
        <div class="tools">
          <button class="quiet" type="button" :disabled="busy" @click="schedule.windows.push({ start: '', end: '' })">新增窗口</button>
          <button class="primary" type="submit" :disabled="busy">保存该范围的全部窗口</button>
        </div>
      </form>
      <p v-else class="empty">{{ busy ? '正在读取…' : '选择技师和日期范围后读取窗口。' }}</p>
    </template>
  </section>
</template>
