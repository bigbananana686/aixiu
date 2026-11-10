<script setup>
import { computed } from 'vue'
import Icon from './Icon.vue'
import { previewDate } from './orders.js'
import { demo } from './api.js'

const props = defineProps({ orders: { type: Array, required: true }, exceptions: { type: Array, required: true } })
const emit = defineEmits(['open-orders', 'open-exceptions'])
const metrics = [
  { label: '工单总量', status: '', icon: 'file' },
  { label: '待技师接单', status: '待接单', icon: 'clock' },
  { label: '待车主确认报价', status: '待确认报价', icon: 'file' },
  { label: '已完工', status: '已完工', icon: 'check' },
]
const priorities = [
  { type: 'DEFERRAL_SLOT_CONFLICT', label: '延期时段冲突', text: '核查延期与后续预约的时间重叠', icon: 'calendar' },
  { type: 'OVER_PLAN_COMPLETION', label: '超计划完工', text: '核对实际完工与承诺结束时间', icon: 'alert' },
]
const statuses = ['待接单', '已接单', '技师已出发', '技师已到达', '待确认报价', '待开工', '保养中', '待确认增项', '已完工', ...(!demo ? ['已归档', '已取消'] : [])]
const distribution = computed(() => statuses.map(label => ({
  label, count: props.orders.filter(order => order.status === label).length,
})))
const maxCount = computed(() => Math.max(1, ...distribution.value.map(item => item.count)))

function countOrders(status) {
  return status ? props.orders.filter(order => order.status === status).length : props.orders.length
}
</script>

<template>
  <div class="dashboard">
    <section class="dashboard-banner" aria-labelledby="banner-title">
      <div>
        <h2 id="banner-title">每一笔工单，都有下一步</h2>
        <p>集中跟进预约接单、报价确认和服务异常，让上门养护有序推进。</p>
        <small v-if="demo">示例排班日 <time :datetime="previewDate">{{ previewDate }}</time></small>
      </div>
      <span class="banner-mark"><Icon name="shield" /></span>
    </section>
    <section class="order-metrics" aria-label="工单概览">
      <button v-for="metric in metrics" :key="metric.label" class="metric-card" type="button"
        :aria-label="'查看' + metric.label + '工单，' + countOrders(metric.status) + ' 笔'"
        @click="emit('open-orders', metric.status)">
        <span class="metric-label">{{ metric.label }} <span class="metric-icon"><Icon :name="metric.icon" /></span></span>
        <span class="metric-value"><strong>{{ countOrders(metric.status) }}</strong> 笔</span>
        <span class="metric-link">查看工单 <Icon name="chevron" /></span>
      </button>
    </section>
    <div class="dashboard-grid">
      <section class="panel" aria-labelledby="priorities-title">
        <header class="panel-heading"><h2 id="priorities-title">优先处理</h2><span>服务异常</span></header>
        <ul class="priority-list">
          <li v-for="item in priorities" :key="item.type">
            <button type="button" @click="emit('open-exceptions', item.type)">
              <span class="priority-icon"><Icon :name="item.icon" /></span>
              <span class="priority-text"><strong>{{ item.label }}</strong><small>{{ item.text }}</small></span>
              <span class="priority-count">{{ exceptions.filter(row => row.type === item.type).length }}</span><Icon name="chevron" />
            </button>
          </li>
        </ul>
        <footer class="priority-footer">
          <span>共 {{ exceptions.length }} 条异常记录</span>
          <button class="text-button" type="button" @click="emit('open-exceptions', '')">查看全部异常 <Icon name="chevron" /></button>
        </footer>
      </section>
      <section class="panel" aria-labelledby="distribution-title">
        <header class="panel-heading"><h2 id="distribution-title">工单进度分布</h2><span>当前状态</span></header>
        <ul class="distribution">
          <li v-for="item in distribution" :key="item.label">
            <span>{{ item.label }}</span>
            <span class="distribution-track" aria-hidden="true">
              <span :style="{ width: (100 * item.count / maxCount) + '%' }"></span>
            </span>
            <strong>{{ item.count }}</strong>
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>
