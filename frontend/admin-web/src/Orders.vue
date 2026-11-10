<script setup>
import { computed, nextTick, reactive, ref } from 'vue'
import Icon from './Icon.vue'
import { api, demo, listAll } from './api.js'
import { exceptionRow, localTime, orderRow } from './live-orders.js'

const props = defineProps({
  orders: { type: Array, required: true }, exceptions: { type: Array, required: true },
  technicians: { type: Object, required: true },
})
const emit = defineEmits(['open-exceptions'])
const emptyFilters = { keyword: '', status: '', tech: '', service: '', date: '' }
const draft = reactive({ ...emptyFilters })
const applied = ref({ ...emptyFilters })
const statuses = computed(() => [...new Set(props.orders.map(order => order.status))])
const technicians = computed(() => [...new Set(props.orders.map(order => order.tech))])
const services = computed(() => [...new Set(props.orders.map(order => order.service))])
const dialog = ref(null)
const selected = ref(null)
const detailError = ref('')
const pendingDetail = ref(false)
const selectedExceptions = computed(() => selected.value?.exceptions || props.exceptions.filter(item => item.orderId === selected.value?.id))
const visibleOrders = computed(() => {
  const { keyword, status, tech, service, date } = applied.value
  const search = keyword.trim().toLowerCase()
  return props.orders.filter(order =>
    (!status || order.status === status)
    && (!tech || order.tech === tech)
    && (!service || order.service === service)
    && (!date || order.date === date)
    && [order.id, order.number, order.car, order.plate, order.tech, order.service].some(field => field?.toLowerCase().includes(search)),
  )
})

function apply() { applied.value = { ...draft } }
function reset(filters = {}) {
  Object.assign(draft, emptyFilters, filters)
  apply()
}

async function showOrder(order) {
  if (pendingDetail.value) return
  detailError.value = ''
  if (!demo) {
    pendingDetail.value = true
    try {
      const path = '/admin/orders/' + order.id
      const [data, quotes] = await Promise.all([api(path), listAll(path + '/quotes')])
      const detail = orderRow(data, props.technicians)
      detail.exceptions = data.exceptions?.map(item => exceptionRow(item, props.technicians))
      detail.quotes = quotes
      const pending = quotes.find(item => item.quoteId === data.pendingQuoteId)
      if (pending) detail.quote = { amount: pending.totalAmount, deadline: localTime(pending.expiresAt) }
      order = detail
    } catch (error) {
      detailError.value = error.message
      return
    } finally {
      pendingDetail.value = false
    }
  }
  selected.value = order
  await nextTick()
  dialog.value.showModal()
}

function openLinkedOrder(id) {
  const order = props.orders.find(item => item.id === id) || (!demo ? { id } : null)
  if (!order) return
  reset({ keyword: id })
  showOrder(order)
}

function viewExceptions() {
  const orderId = selected.value.id
  dialog.value.close()
  emit('open-exceptions', orderId)
}

defineExpose({ filterStatus: status => reset({ status }), openLinkedOrder })
</script>

<template>
  <section class="ledger order-ledger" aria-label="工单列表">
    <form class="query-grid" @submit.prevent="apply">
      <label>关键词<input v-model="draft.keyword" type="search" :placeholder="demo ? '搜索工单号、车型或车牌' : '搜索工单号、车辆编号或技师'" autocomplete="off"></label>
      <label>工单状态
        <select v-model="draft.status"><option value="">全部</option><option v-for="status in statuses" :key="status">{{ status }}</option></select>
      </label>
      <label>上门技师
        <select v-model="draft.tech"><option value="">全部</option><option v-for="tech in technicians" :key="tech">{{ tech }}</option></select>
      </label>
      <label v-if="demo">服务项目
        <select v-model="draft.service"><option value="">全部</option><option v-for="service in services" :key="service">{{ service }}</option></select>
      </label>
      <label>预约日期<input v-model="draft.date" type="date"></label>
      <div class="query-actions">
        <button class="primary" type="submit"><Icon name="search" /> 查询</button>
        <button class="quiet" type="button" @click="reset()">重置</button>
      </div>
    </form>
    <header class="ledger-heading"><h2>工单中心列表</h2><span role="status">共 {{ visibleOrders.length }} 条记录</span></header>
    <p v-if="detailError" class="empty form-error" role="alert">{{ detailError }}</p>
    <div class="table-scroll" tabindex="0" role="region" aria-label="工单表格">
      <table class="order-table">
        <thead>
          <tr>
            <th scope="col">工单号</th><th scope="col">预约车辆</th><th v-if="demo" scope="col">服务项目</th>
            <th scope="col">工单状态</th><th scope="col">上门技师</th><th scope="col">预约时间</th><th scope="col">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="order in visibleOrders" :key="order.id">
            <td class="order-id">{{ order.number || order.id }}</td>
            <td>{{ order.car }}<small>{{ order.plate }}</small></td>
            <td v-if="demo">{{ order.service }}</td>
            <td><span class="status" :class="order.tone">{{ order.status }}</span></td>
            <td>{{ order.tech }}</td>
            <td><time :datetime="order.date">{{ order.date }}</time><small>{{ order.window }}</small></td>
            <td><button class="text-button" type="button" :disabled="pendingDetail" :aria-label="'查看详情 ' + (order.number || order.id)" @click="showOrder(order)">查看详情 <Icon name="chevron" /></button></td>
          </tr>
          <tr v-if="!visibleOrders.length"><td class="empty" :colspan="demo ? 7 : 6">没有符合条件的工单。可以调整筛选条件或重置。</td></tr>
        </tbody>
      </table>
    </div>
    <dialog ref="dialog" class="order-dialog" aria-labelledby="order-detail-title" @close="selected = null"
      @click.self="dialog.close()">
      <div v-if="selected" class="drawer-content">
        <header class="drawer-heading">
          <div><h2 id="order-detail-title">工单详情</h2><p class="order-id">{{ selected.number || selected.id }}</p></div>
          <button class="icon-button" type="button" aria-label="关闭工单详情" autofocus @click="dialog.close()"><Icon name="close" /></button>
        </header>
        <section class="detail">
          <header class="detail-head">
            <h3>{{ selected.car }}</h3><p>{{ selected.plate }} / {{ selected.service }}</p>
            <span class="status" :class="selected.tone">{{ selected.status }}</span>
          </header>
          <dl>
            <div v-if="selected.address"><dt>上门地址</dt><dd>{{ selected.address }}</dd></div>
            <div><dt>预约窗口</dt><dd>{{ selected.date }} {{ selected.window }}</dd></div>
            <div><dt>上门技师</dt><dd>{{ selected.tech }}</dd></div>
          </dl>
          <div v-if="selected.quote" class="quote">
            <span>待确认报价</span><strong>¥{{ selected.quote.amount }}</strong><small>确认截止 {{ selected.quote.deadline }}</small>
          </div>
        </section>
        <section v-if="selected.quotes?.length" class="detail progress">
          <h3>报价记录</h3>
          <details v-for="quote in selected.quotes" :key="quote.quoteId">
            <summary>第 {{ quote.version }} 版 · ¥{{ quote.totalAmount }} · {{ { PENDING: '待确认', APPROVED: '已确认', REJECTED: '已拒绝', EXPIRED: '已过期', SUPERSEDED: '已替换', WITHDRAWN: '已撤回' }[quote.status] || quote.status }}</summary>
            <p v-for="item in quote.items" :key="item.lineKey">{{ item.nameSnapshot }} × {{ item.quantity }} · ¥{{ item.unitPrice }}</p>
          </details>
        </section>
        <section v-if="selectedExceptions.length" class="detail progress" aria-label="本单异常">
          <h3>服务异常</h3>
          <p v-for="item in selectedExceptions" :key="item.id" class="exception-summary"><strong>{{ item.label }}</strong><small>{{ item.detail }}</small></p>
          <button class="text-button" type="button" @click="viewExceptions">查看异常记录 <Icon name="chevron" /></button>
        </section>
        <section class="detail progress" aria-label="服务记录">
          <h3>服务记录</h3>
          <ol><li v-for="event in selected.events" :key="event.id ?? event.time"><time>{{ event.time }}</time>{{ event.title }}</li></ol>
        </section>
      </div>
    </dialog>
  </section>
</template>
