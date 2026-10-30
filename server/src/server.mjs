// 拾日云：替持有授权码的用户调用转写和 AI，按“有效转录时长”从授权码额度里扣费。
// 有效时长由服务端按收到的音频长度计算（16 kHz、16-bit 单声道），App 在本地已经去掉了静音和环境音。
// 不保存音频，也不保存转写文字；只记账。没有注册、没有试用：每个请求都必须带有效的授权码。
import { createServer } from 'node:http'
import { charge, countAssist, licenseFor, summary } from './db.mjs'
import { UpstreamError, generateJson, recognize } from './google.mjs'
import { costFor, pricing } from './pricing.mjs'
import { ENGLISH_RULE, TASKS } from './prompts.mjs'

const PORT = Number(process.env.PORT || 8787)
const ASSISTS_PER_DAY = Number(process.env.ASSISTS_PER_DAY || process.env.DIGESTS_PER_DAY || 120)
const MAX_BODY = 4 * 1024 * 1024
const MAX_AUDIO_SECONDS = 60
const MAX_INPUT_CHARS = 120_000
const MAX_FAILURES_PER_HOUR = 20

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
  return license
}

const routes = {
  'GET /v1/pricing': () => ({ ...pricing, priceMicrosPerMinute: pricing.priceMicrosPerMinute }),

  'GET /v1/me': (req) => ({ ...summary(licensed(req)), priceMicrosPerMinute: pricing.priceMicrosPerMinute }),

  'POST /v1/transcribe': async (req) => {
    const license = licensed(req)
    const { audio } = await readJson(req)
    if (typeof audio !== 'string' || !audio) throw new HttpError(400, 'audio required')
    const bytes = Buffer.byteLength(audio, 'base64')
    const seconds = Math.ceil(bytes / 2 / 16_000)
    if (seconds > MAX_AUDIO_SECONDS) throw new HttpError(413, 'audio longer than 60 s')
    const cost = costFor(seconds)
    if (license.balance_micros < cost) throw new HttpError(402, 'license balance used up')
    const { results = [] } = await recognize(audio)
    // 转写成功才扣费；扣费时再核对一次余额，防止并发透支。
    if (!charge(license.hash, seconds, cost)) throw new HttpError(402, 'license balance used up')
    return { results }
  },

  // 每日整理与主动提醒：提示词由服务端保管，客户端只给任务类型、语言和转写内容。费用已包含在转录单价里。
  'POST /v1/assist': async (req) => {
    const license = licensed(req)
    if (license.balance_micros <= 0) throw new HttpError(402, 'license balance used up')
    const { kind, lang, input } = await readJson(req)
    const task = TASKS[kind]
    if (!task) throw new HttpError(400, 'unknown kind')
    if (typeof input !== 'string' || !input) throw new HttpError(400, 'input required')
    if (input.length > MAX_INPUT_CHARS) throw new HttpError(413, 'input too long')
    if (!countAssist(license.hash, ASSISTS_PER_DAY)) throw new HttpError(429, 'daily limit reached')
    return { text: await generateJson(task.system + (lang === 'en' ? ENGLISH_RULE : ''), input, task.schema) }
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
  console.log(`dayweave cloud on :${PORT}, $${(pricing.priceMicrosPerMinute / 1e6).toFixed(4)}/min`)
})
