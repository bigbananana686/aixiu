const order = {
  id: 'AC-1103-018',
  car: '大众朗逸',
  plate: '粤B·•••38',
  service: '机油和机滤更换',
  appointment: '11 月 03 日 10:30–11:30',
  address: '云栖花园 2 栋地面车位',
  tech: '陈师傅',
  quote: {
    total: '328.00',
    deadline: '10:55',
    items: [
      { name: '全合成机油', amount: '239.00' },
      { name: '机油滤清器', amount: '39.00' },
      { name: '更换工时', amount: '50.00' }
    ]
  },
  progress: [
    { time: '10:42', title: '技师发布初始报价' },
    { time: '10:34', title: '技师到达' },
    { time: '10:10', title: '技师出发' },
    { time: '09:02', title: '技师接单' }
  ]
}

module.exports = { order }
