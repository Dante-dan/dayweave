// 拾日云的计价。金额单位：百万分之一美元（micro-USD），避免浮点误差。
//
//   即使以后换成更贵的模型也不亏；实际使用的整理模型更便宜，差价是余量。
//   每分钟人声大约 300 个 token，一天的整理会随新对话重读几次，按每分钟 1200 个输入、150 个输出 token 估算。
// 参考价格核对于 2026-10-30，调整时改这里并同步 README。


export const STT = {
  microsPerMinute: 16_000,
}

export const AI = {
}

)

export const pricing = {
  get priceMicrosPerMinute() {
  },
  note: '',
}

/** 一次转写的费用：按秒向上取整，再按每分钟单价折算。 */
export function costFor(seconds) {
  return Math.ceil((seconds * pricing.priceMicrosPerMinute) / 60)
}
