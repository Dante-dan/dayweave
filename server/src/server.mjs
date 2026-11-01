// 拾日云：替持有授权码的用户调用转写和 AI，按“有效转录时长”从授权码额度里扣费。
// 有效时长由服务端按收到的音频长度计算（16 kHz、16-bit 单声道），App 在本地已经去掉了静音和环境音。
// 不保存音频，也不保存转写文字；只记账。没有注册、没有试用：每个请求都必须带有效的授权码。
// App Store / Google Play 的订阅核验后换成同样的凭证（DS-…），余额每个周期按方案重置。
import { createServer } from 'node:http'
import { createHash } from 'node:crypto'
import { charge, chargeAssist, countAssist, licenseFor, summary, touchDevice, upsertSubscription } from './db.mjs'
import { adminAllowed, createLicenses, licenseDetail, listLicenses, overview, updateLicense } from './admin.mjs'
import { UpstreamError, generateJson, recognize } from './google.mjs'
import { PLANS, grantFor } from './plans.mjs'
import { assistPrice, costFor, llmCost, pricing, sttCost } from './pricing.mjs'
import { ProofError, verifyApple, verifyGoogle } from './stores.mjs'
import { ENGLISH_RULE, TASKS } from './prompts.mjs'

const PORT = Number(process.env.PORT || 8787)
const ASSISTS_PER_DAY = Number(process.env.ASSISTS_PER_DAY || process.env.DIGESTS_PER_DAY || 80)
const MAX_BODY = 4 * 1024 * 1024
const MAX_AUDIO_SECONDS = 60
const MAX_INPUT_CHARS = 120_000
const MAX_FAILURES_PER_HOUR = 20
const SUBSCRIPTION_SECRET = process.env.SUBSCRIPTION_SECRET || ''

// 授权码输错太多次的来源，一小时内直接拒绝，防止猜码。
const failures = new Map()
const clientIp = (req) => req.headers['cf-connecting-ip'] || req.socket.remoteAddress
function blocked(ip) {
  const hour = Math.floor(Date.now() / 3_600_000)
  return (failures.get(`${ip}:${hour}`) || 0) >= MAX_FAILURES_PER_HOUR
}
function fail(ip) {
  const hour = Math.floor(Date.now() / 3_600_000)
  const key = `${ip}:${hour}`
  failures.set(key, (failures.get(key) || 0) + 1)
  if (failures.size > 10_000) failures.clear()
}

class HttpError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

async function readJson(req) {
  let size = 0
  const chunks = []
  for await (const chunk of req) {
    size += chunk.length
    if (size > MAX_BODY) throw new HttpError(413, 'body too large')
    chunks.push(chunk)
  }
  if (!size) return {}
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'))
  } catch {
    throw new HttpError(400, 'invalid json')
  }
}

/** 校验授权码（Authorization: Bearer <授权码>）。 */
function licensed(req) {
  const ip = clientIp(req)
  if (blocked(ip)) throw new HttpError(429, 'too many invalid codes')
  const code = (req.headers.authorization || '').replace(/^Bearer\s+/i, '')
  const license = code && licenseFor(code)
  if (!license) {
    fail(ip)
    throw new HttpError(401, 'invalid license code')
  }
  if (license.expires_at && license.expires_at < Date.now()) throw new HttpError(402, 'subscription expired')
  // 设备：新版 App 带安装标识；旧版没有，按来源 + UA 估算。只存哈希。
  const given = String(req.headers['x-dayweave-device'] || '').slice(0, 64)
  const device = createHash('sha256').update(given ? `d:${given}` : `e:${ip}|${req.headers['user-agent'] || ''}`).digest('hex')
  try {
    touchDevice(license.hash, device, String(req.headers['x-dayweave-platform'] || '').slice(0, 16), !given)
  } catch (e) {
    console.error('device', e.message)
  }
  return { ...license, device }
}

function admin(req) {
  if (!adminAllowed(req)) throw new HttpError(404, 'not found')
}

/** 模型偶尔会在某个字段里无限重复。主动提醒：丢掉失控的那几条；每日整理：整份作废，让 App 稍后重试。 */
function sanitize(kind, text, maxChars) {
  let value
  try {
    value = JSON.parse(text)
  } catch {
    throw new UpstreamError(502, 'invalid model json')
  }
  const tooLong = (v) => typeof v === 'string' ? v.length > maxChars : v && typeof v === 'object' ? Object.values(v).some(tooLong) : false
  if (kind === 'agent') value.actions = (value.actions || []).filter((a) => !tooLong(a))
  else if (tooLong(value)) throw new UpstreamError(502, 'runaway model output')
  return JSON.stringify(value)
}

const rateOf = (license) => license.price_micros_per_minute || pricing.priceMicrosPerMinute

const routes = {
  'GET /v1/pricing': () => ({ priceMicrosPerMinute: pricing.priceMicrosPerMinute }),

  'GET /v1/me': (req) => {
    const license = licensed(req)
    return { ...summary(license), priceMicrosPerMinute: rateOf(license) }
  },

  'GET /v1/plans': () => ({
    plans: Object.entries(PLANS).map(([productId, p]) => ({ productId, ...p, grantMicros: grantFor(productId) })),
  }),

  // 购买或恢复订阅：App 交来商店凭证（App Store 的签名交易 / Google Play 的 purchaseToken），核验后返回拾日云凭证。
  'POST /v1/subscription': async (req) => {
    const ip = clientIp(req)
    if (blocked(ip)) throw new HttpError(429, 'too many invalid purchases')
    if (!SUBSCRIPTION_SECRET) throw new HttpError(503, 'subscriptions not configured')
    const { store, signedTransaction, purchaseToken } = await readJson(req)
    let proof
    try {
      if (store === 'apple') proof = verifyApple(signedTransaction)
      else if (store === 'google') proof = await verifyGoogle(purchaseToken)
      else throw new HttpError(400, 'unknown store')
    } catch (e) {
      if (!(e instanceof ProofError)) throw e
      fail(ip)
      throw new HttpError(400, e.message)
    }
    const plan = PLANS[proof.productId]
    if (!plan) throw new HttpError(400, 'unknown product')
    if (!proof.active) throw new HttpError(402, 'subscription expired')
    const { code } = upsertSubscription({ ...proof, plan: plan.id, period: plan.period }, grantFor(proof.productId), SUBSCRIPTION_SECRET)
    return { licenseCode: code, plan: plan.id, ...summary(licenseFor(code)), priceMicrosPerMinute: pricing.priceMicrosPerMinute }
  },

  'POST /v1/transcribe': async (req) => {
    const license = licensed(req)
    const { audio } = await readJson(req)
    if (typeof audio !== 'string' || !audio) throw new HttpError(400, 'audio required')
    const bytes = Buffer.byteLength(audio, 'base64')
    const seconds = Math.ceil(bytes / 2 / 16_000)
    if (seconds > MAX_AUDIO_SECONDS) throw new HttpError(413, 'audio longer than 60 s')
    const cost = costFor(seconds, rateOf(license))
    if (license.balance_micros < cost) throw new HttpError(402, 'license balance used up')
    const { results = [] } = await recognize(audio)
    // 转写成功才扣费；扣费时再核对一次余额，防止并发透支。
    if (!charge(license.hash, seconds, cost, sttCost(seconds), license.device)) throw new HttpError(402, 'license balance used up')
    return { results }
  },

  // 每日整理、主动提醒与助手对话：提示词由服务端保管，客户端只给任务类型、语言和输入。
  // 授权码按实际用量从余额扣费；订阅按方案时长计费，AI 调用不另扣。
  'POST /v1/assist': async (req) => {
    const license = licensed(req)
    if (license.balance_micros <= 0) throw new HttpError(402, 'license balance used up')
    const { kind, lang, input } = await readJson(req)
    const task = TASKS[kind]
    if (!task) throw new HttpError(400, 'unknown kind')
    if (typeof input !== 'string' || !input) throw new HttpError(400, 'input required')
    if (input.length > MAX_INPUT_CHARS) throw new HttpError(413, 'input too long')
    if (!countAssist(license.hash, ASSISTS_PER_DAY)) throw new HttpError(429, 'daily limit reached')
    const { text, tokensIn, tokensOut } = await generateJson(task.system + (lang === 'en' ? ENGLISH_RULE : ''), input, task.schema, task.maxOutputTokens)
    const costMicros = llmCost(tokensIn, tokensOut)
    const subscribed = String(license.label || '').startsWith('sub:')
    chargeAssist(license.hash, kind, { micros: subscribed ? 0 : assistPrice(costMicros), costMicros, tokensIn, tokensOut, device: license.device })
    return { text: sanitize(kind, text, task.maxFieldChars) }
  },

  // 后台：没有 ADMIN_TOKEN 或令牌不对时一律 404，不暴露接口存在。
  'GET /v1/admin/overview': (req) => (admin(req), overview()),
  'GET /v1/admin/licenses': (req) => (admin(req), listLicenses()),
  'GET /v1/admin/license': (req) => {
    admin(req)
    const hash = new URL(req.url, 'http://localhost').searchParams.get('hash') || ''
    const detail = licenseDetail(hash)
    if (!detail) throw new HttpError(404, 'license not found')
    return detail
  },
  'POST /v1/admin/licenses': async (req) => {
    admin(req)
    try {
      return createLicenses(await readJson(req))
    } catch (e) {
      throw e instanceof HttpError ? e : new HttpError(400, e.message)
    }
  },
  'POST /v1/admin/license': async (req) => {
    admin(req)
    let detail
    try {
      detail = updateLicense(await readJson(req))
    } catch (e) {
      throw e instanceof HttpError ? e : new HttpError(400, e.message)
    }
    if (!detail) throw new HttpError(404, 'license not found')
    return detail
  },
}

createServer(async (req, res) => {
  const path = new URL(req.url, 'http://localhost').pathname
  const handler = routes[`${req.method} ${path}`]
  const send = (status, body) => {
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
    res.end(JSON.stringify(body))
  }
  if (!handler) return send(404, { error: 'not found' })
  const started = Date.now()
  try {
    send(200, await handler(req))
  } catch (e) {
    const status = e instanceof HttpError ? e.status : e instanceof UpstreamError ? 502 : 500
    if (status >= 500) console.error(path, e.message)
    // 上游的原始报错可能带项目信息，不回传给客户端。
    send(status, { error: status >= 500 ? 'service unavailable' : e.message })
  } finally {
    // 只记路径、状态和耗时，不记内容、不记授权码。
    console.log(`${req.method} ${path} ${res.statusCode} ${Date.now() - started}ms`)
  }
}).listen(PORT, '0.0.0.0', () => {
  console.log(`dayweave cloud on :${PORT}`)
})
