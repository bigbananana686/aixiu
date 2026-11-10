const brands = [
  { name: '宝马', mark: 'BMW', models: [['1系', '118i', '120i', '125i'], ['3系', '320i', '325Li', '330i'], ['SUV', 'X1', 'X3', 'X5']] },
  { name: '福特', mark: 'Ford', models: [['轿车', '福克斯', '蒙迪欧'], ['SUV', '锐界', '探险者']] },
  { name: '本田', mark: 'H', models: [['轿车', '思域', '雅阁', '飞度'], ['SUV', 'CR-V', '皓影']] },
  { name: '丰田', mark: 'TOYOTA', models: [['轿车', '卡罗拉', '凯美瑞', '亚洲龙'], ['SUV', '汉兰达', 'RAV4荣放']] },
  { name: '别克', mark: 'BUICK', models: [['轿车', '君威', '君越', '英朗'], ['SUV', '昂科威']] },
  { name: '大众', mark: 'VW', models: [['轿车', '朗逸', '速腾', '帕萨特'], ['SUV', '途观L', '探岳']] },
  { name: '现代', mark: 'HYUNDAI', models: [['轿车', '伊兰特', '索纳塔'], ['SUV', '途胜']] },
  { name: '日产', mark: 'NISSAN', models: [['轿车', '轩逸', '天籁'], ['SUV', '逍客', '奇骏']] },
  { name: '奥迪', mark: 'AUDI', models: [['轿车', 'A3', 'A4L', 'A6L'], ['SUV', 'Q3', 'Q5L']] },
  { name: '雪佛兰', mark: 'CHEVROLET', models: [['轿车', '科鲁泽', '迈锐宝XL'], ['SUV', '探界者']] }
].map(brand => ({
  ...brand,
  groups: brand.models.map(([name, ...models]) => ({ name, models }))
}))

module.exports = { brands }
