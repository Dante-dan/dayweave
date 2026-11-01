// 订阅方案（App Store / Google Play 自动续期）。两边商品 ID 相同。
// 每个月发放固定的人声时长，折成余额（按当前单价），月底不结转；年付同样按月发放，每 30 天重置一次。
// 年付 = 10 个月的价格；专业和全天只有月付（App Store 订阅单价上限 $1,000）。
// 改价格时同步 site/content.mjs、两个 App 内的方案和两边商店后台。
import { pricing } from './pricing.mjs'

const TIERS = {
  lite: { hours: 5, monthly: 19.99, yearly: 199.99 },
  plus: { hours: 30, monthly: 99.99, yearly: 999.99 },
  pro: { hours: 70, monthly: 199.99 },
  max: { hours: 180, monthly: 499.99 },
}

export const PLANS = Object.fromEntries(Object.entries(TIERS).flatMap(([id, t]) => [
  [`app.dayweave.cloud.${id}.monthly`, { id, period: 'monthly', usd: t.monthly, hours: t.hours }],
  ...(t.yearly ? [[`app.dayweave.cloud.${id}.yearly`, { id, period: 'yearly', usd: t.yearly, hours: t.hours }]] : []),
]))

/** 每个月发放的余额。 */
export const grantFor = (productId) => {
  const plan = PLANS[productId]
  return plan ? plan.hours * 60 * pricing.priceMicrosPerMinute : 0
}

export const MONTH_MS = 30 * 24 * 3600 * 1000
