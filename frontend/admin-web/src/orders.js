export const previewDate = '2026-11-03'

export const orders = [
  {
    id: 'AC-1103-006', date: previewDate, car: '丰田卡罗拉', plate: '粤B·•••62',
    tech: '周师傅', status: '已完工', tone: 'done', service: '机油和机滤更换',
    address: '海棠苑 1 栋地面车位', window: '09:30–10:30',
    events: [{ time: '10:21', title: '技师完成保养' }, { time: '09:39', title: '技师到达' }]
  },
  {
    id: 'AC-1103-018', date: previewDate, car: '大众朗逸', plate: '粤B·•••38',
    tech: '陈师傅', status: '待确认报价', tone: 'pending', service: '机油和机滤更换',
    address: '云栖花园 2 栋地面车位', window: '10:30–11:30',
    quote: { amount: '328.00', deadline: '10:55' },
    events: [{ time: '10:42', title: '技师发布初始报价' }, { time: '10:34', title: '技师到达' }, { time: '10:10', title: '技师出发' }, { time: '09:02', title: '技师接单' }]
  },
  {
    id: 'AC-1103-023', date: previewDate, car: '本田思域', plate: '粤B·•••15',
    tech: '陈师傅', status: '待接单', tone: 'open', service: '更换空调滤芯',
    address: '香樟公馆东门地面车位', window: '13:00–14:00',
    events: [{ time: '10:46', title: '车主提交预约' }]
  }
]

export const exceptions = [
  { id: 'E-001', orderId: 'AC-1103-018', time: '11-03 11:20', tech: '陈师傅',
    type: 'DEFERRAL_SLOT_CONFLICT', label: '延期时段冲突',
    detail: '申请延期至 13:20，与 13:00 的下一笔预约重叠。原承诺结束时间保持 11:30。' },
  { id: 'E-002', orderId: 'AC-1103-006', time: '11-03 10:21', tech: '周师傅',
    type: 'OVER_PLAN_COMPLETION', label: '超计划完工',
    detail: '演示超计划记录，实际完工比当时承诺结束晚 6 分钟。' }
]
