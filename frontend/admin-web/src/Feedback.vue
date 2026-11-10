<script setup>
import { computed, ref } from 'vue'
import Icon from './Icon.vue'

const props = defineProps({ orders: { type: Array, required: true } })
const emit = defineEmits(['open-order'])
const labels = { FEEDBACK: '问题反馈', COMPLAINT: '服务投诉' }
const rows = ref([])
const type = ref('')
const query = ref('')
const loading = ref(false)
const error = ref('')
const visible = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  return rows.value.filter(item => (!type.value || item.type === type.value)
    && [item.owner, item.orderId, item.detail].some(value => value.toLowerCase().includes(keyword)))
})
const knownOrders = computed(() => new Set(props.orders.map(order => order.id)))
const format = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'short',
})

async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const response = await fetch('/api/preview/feedback', { headers: { Authorization: 'Bearer demo-admin' } })
    const body = await response.json()
    if (!response.ok) throw new Error(body.message)
    rows.value = body.data
  } catch {
    error.value = '无法读取反馈记录。请确认本地预览服务正在运行后刷新。'
  } finally {
    loading.value = false
  }
}

defineExpose({ load })
</script>

<template>
  <section class="ledger feedback-ledger" aria-label="反馈与投诉记录">
    <div class="tools">
      <label class="search">记录类型
        <select v-model="type"><option value="">全部</option><option v-for="(label, key) in labels" :key="key" :value="key">{{ label }}</option></select>
      </label>
      <label class="search">关键词<input v-model="query" type="search" placeholder="搜索车主、工单号或说明"></label>
      <button class="quiet" type="button" :disabled="loading" @click="load"><Icon name="refresh" /> 刷新记录</button>
    </div>
    <header class="ledger-heading"><h2>车主提交记录</h2><span role="status">{{ loading ? '正在读取' : '共 ' + visible.length + ' 条记录' }}</span></header>
    <p v-if="error" class="form-error feedback-error" role="alert">{{ error }}</p>
    <div class="table-scroll" tabindex="0" role="region" aria-label="反馈表格">
      <table>
        <thead><tr><th scope="col">提交时间</th><th scope="col">类型</th><th scope="col">车主</th><th scope="col">关联工单</th><th scope="col">问题说明</th></tr></thead>
        <tbody>
          <tr v-for="item in visible" :key="item.id">
            <td><time :datetime="item.createdAt">{{ format.format(new Date(item.createdAt)) }}</time></td>
            <td><span class="status" :class="{ pending: item.type === 'COMPLAINT' }">{{ labels[item.type] }}</span></td>
            <td>{{ item.owner }}</td>
            <td>
              <button v-if="knownOrders.has(item.orderId)" class="text-button" type="button" @click="emit('open-order', item.orderId)">{{ item.orderId }}</button>
              <span v-else>{{ item.orderId || '—' }}</span>
            </td>
            <td class="feedback-description">{{ item.detail }}</td>
          </tr>
          <tr v-if="!visible.length"><td colspan="5" class="empty">{{ error ? '反馈记录暂不可用。' : loading ? '正在读取记录。' : '没有符合条件的反馈记录。' }}</td></tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
