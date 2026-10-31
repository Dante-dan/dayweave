// 拾日云的计价：按“有效转录时长”收费。金额单位：百万分之一美元（micro-USD），避免浮点误差。
// 单价由服务端的 .env（PRICE_MICROS_PER_MINUTE）决定；定价依据只记在本地，不进仓库。

export const pricing = {
  priceMicrosPerMinute: Number(process.env.PRICE_MICROS_PER_MINUTE || 80_800),
}

/** 一次转写的费用：按秒向上取整，再按每分钟单价折算。授权码可以带自己的单价（早期售出的码沿用当时的价格）。 */
export function costFor(seconds, priceMicrosPerMinute = pricing.priceMicrosPerMinute) {
  return Math.ceil((seconds * priceMicrosPerMinute) / 60)
}
