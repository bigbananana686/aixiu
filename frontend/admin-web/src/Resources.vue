<script setup>
import { computed, ref } from 'vue'

const props = defineProps({ section: { type: String, required: true } })
const catalog = ref([
  { id: 1, name: '机油和机滤更换', price: 328, minutes: 60, enabled: true },
  { id: 2, name: '更换空调滤芯', price: 118, minutes: 30, enabled: true },
  { id: 3, name: '蓄电池检查', price: 49, minutes: 30, enabled: false }
])
const schedule = ref([
  { id: 1, tech: '陈师傅', date: '2026-11-04', start: '10:00', end: '16:00' },
  { id: 2, tech: '周师傅', date: '2026-11-04', start: '09:00', end: '12:00' }
])
const isCatalog = computed(() => props.section === 'catalog')
const entries = computed(() => isCatalog.value ? catalog.value : schedule.value)
const draft = ref(null)
const notice = ref('')
const error = ref('')
const currency = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' })

function edit(entry) {
  draft.value = { ...entry }
  notice.value = ''
  error.value = ''
}

function close() {
  draft.value = null
  error.value = ''
}

function save() {
  const value = draft.value
  if (isCatalog.value) {
    if (!value.name.trim() || !Number.isFinite(value.price) || value.price < 0
      || !Number.isInteger(value.minutes) || value.minutes <= 0) {
      error.value = '请填写服务名称、有效价格和正整数分钟。'
      return
    }
    value.name = value.name.trim()
  } else if (!value.date || !value.start || !value.end || value.start >= value.end) {
    error.value = '请选择日期，结束时间须晚于开始时间。'
    return
  }
  const index = entries.value.findIndex(entry => entry.id === value.id)
  if (index >= 0) entries.value[index] = { ...value }
  else entries.value.push({ ...value, id: Math.max(...entries.value.map(entry => entry.id)) + 1 })
  notice.value = '已更新当前预览数据。'
  close()
}

function addService() {
  edit({ name: '', price: 0, minutes: 30, enabled: true })
}
</script>

<template>
  <div class="split resource-split">
    <section class="ledger" :aria-label="isCatalog ? '服务项目' : '工作窗口'">
      <div v-if="isCatalog" class="tools resource-tools">
        <button class="primary" type="button" @click="addService">新增服务项目</button>
      </div>
      <div class="table-scroll" tabindex="0" role="region" :aria-label="isCatalog ? '服务目录表格' : '技师排班表格'">
      <table v-if="isCatalog">
        <thead><tr><th scope="col">保养项目</th><th scope="col">参考价</th><th scope="col">时长</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead>
        <tbody>
          <tr v-for="entry in entries" :key="entry.id">
            <td>{{ entry.name }}</td><td>{{ currency.format(entry.price) }}</td><td>{{ entry.minutes }} 分钟</td>
            <td><span class="status" :class="entry.enabled ? 'done' : ''">{{ entry.enabled ? '已启用' : '已停用' }}</span></td>
            <td><button class="vehicle" type="button" :aria-label="'编辑' + entry.name" @click="edit(entry)">编辑</button></td>
          </tr>
        </tbody>
      </table>
      <table v-else>
        <thead><tr><th scope="col">技师</th><th scope="col">日期</th><th scope="col">工作窗口</th><th scope="col">操作</th></tr></thead>
        <tbody>
          <tr v-for="entry in entries" :key="entry.id">
            <td>{{ entry.tech }}</td><td>{{ entry.date }}</td><td>{{ entry.start }}–{{ entry.end }}</td>
            <td><button class="vehicle" type="button" :aria-label="'调整' + entry.tech + '窗口'" @click="edit(entry)">调整</button></td>
          </tr>
        </tbody>
      </table>
      </div>
      <footer aria-live="polite">{{ notice || entries.length + (isCatalog ? ' 项服务' : ' 个工作窗口') }}</footer>
    </section>
    <aside class="detail resource-detail" :aria-label="isCatalog ? '项目详情' : '窗口详情'">
      <div class="detail-head"><h2>{{ isCatalog ? '保养项目' : '工作窗口' }}</h2><p>{{ isCatalog ? '价格和时长影响可预约服务' : '上门预约须落在技师工作时段内' }}</p></div>
      <form v-if="draft" class="resource-form" @submit.prevent="save">
        <template v-if="isCatalog">
          <label>服务名称<input v-model="draft.name" required maxlength="30"></label>
          <label>参考价（元）<input v-model.number="draft.price" type="number" min="0" step=".01" required></label>
          <label>时长（分钟）<input v-model.number="draft.minutes" type="number" min="1" required></label>
          <label class="inline-check"><input v-model="draft.enabled" type="checkbox"> 启用项目</label>
        </template>
        <template v-else>
          <strong>{{ draft.tech }}</strong>
          <label>日期<input v-model="draft.date" type="date" required></label>
          <label>开始时间<input v-model="draft.start" type="time" required></label>
          <label>结束时间<input v-model="draft.end" type="time" required></label>
        </template>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <div class="form-actions"><button class="primary" type="submit">保存</button><button class="quiet" type="button" @click="close">取消</button></div>
      </form>
      <p v-else class="empty">选择表格中的{{ isCatalog ? '项目' : '窗口' }}查看并修改。</p>
    </aside>
  </div>
</template>
