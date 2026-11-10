const guidance = {
  reserved: '核对车辆位置和预约时间，再决定是否接单。',
  accepted: '准备出发时更新工单，车主会看到服务进度。',
  departed: '找到车辆后确认到达，再检查车辆并报价。',
  arrived: '核对服务内容，填写初始报价发给车主。',
  quoted: '车主确认报价后可开工。',
  extraQuoted: '等待车主确认增项。可替换或撤回待确认报价，已批准服务范围会保留。',
  ready: '车主已确认报价，可以开始保养。',
  working: '完成报价内的服务后，记录完工。',
  completed: '保养已完成，等待车主验收归档。',
  closed: '车主已验收，工单已归档。',
  cancelled: '这笔工单已取消。'
}

module.exports = { guidance }
