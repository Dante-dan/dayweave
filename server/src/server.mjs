// 拾日云：替用户调用转写和 AI，按“有效转录时长”计费。
// 有效时长由服务端按收到的音频长度计算（16 kHz、16-bit 单声道），App 在本地已经去掉了静音和环境音。
// 不保存音频，也不保存转写文字；只记账。
import { createServer } from 'node:http'
import { accountByToken, charge, countDigest, createAccount, redeem, summary } from './db.mjs'
import { UpstreamError, generateJson, recognize } from './google.mjs'
import { costFor, pricing } from './pricing.mjs'

const PORT = Number(process.env.PORT || 8787)
const TRIAL_MINUTES = Number(process.env.TRIAL_MINUTES ?? 30)
const DIGESTS_PER_DAY = Number(process.env.DIGESTS_PER_DAY || 200)
const MAX_BODY = 4 * 1024 * 1024
const MAX_AUDIO_SECONDS = 60

// 同一 IP 每小时最多创建几个账户，防止反复领体验额度。
const signups = new Map()
function allowSignup(ip) {
  const hour = Math.floor(Date.now() / 3_600_000)
  const key = `${ip}:${hour}`
  const n = (signups.get(key) || 0) + 1
  signups.set(key, n)
  if (signups.size > 10_000) signups.clear()
  return n <= 5
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

class HttpError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

function authed(req) {
  const token = (req.headers.authorization || '').replace(/^Bearer\s+/i, '')
  const account = token && accountByToken(token)
  if (!account) throw new HttpError(401, 'unauthorized')
  return account
}

const routes = {
  'GET /v1/pricing': () => ({ ...pricing, priceMicrosPerMinute: pricing.priceMicrosPerMinute }),

  'POST /v1/accounts': (req) => {
    // 经 Cloudflare Tunnel 进来时，真实来源在 CF-Connecting-IP；服务只监听本机，这个头不会被外部伪造。
    const ip = req.headers['cf-connecting-ip'] || req.socket.remoteAddress
    if (!allowSignup(ip)) throw new HttpError(429, 'too many accounts')
    return createAccount(TRIAL_MINUTES * pricing.priceMicrosPerMinute)
  },

  'GET /v1/me': (req) => {
    const account = authed(req)
    return { ...summary(account.id), priceMicrosPerMinute: pricing.priceMicrosPerMinute }
  },

  'POST /v1/redeem': async (req) => {
    const account = authed(req)
    const { code } = await readJson(req)
    const result = redeem(account.id, String(code || '').trim().toUpperCase())
    if (result === 'missing') throw new HttpError(404, 'code not found')
    if (result === 'used') throw new HttpError(409, 'code already used')
    return summary(account.id)
  },

  'POST /v1/transcribe': async (req) => {
    const account = authed(req)
    const { audio } = await readJson(req)
    if (typeof audio !== 'string' || !audio) throw new HttpError(400, 'audio required')
    const bytes = Buffer.byteLength(audio, 'base64')
    const seconds = Math.ceil(bytes / 2 / 16_000)
    if (seconds > MAX_AUDIO_SECONDS) throw new HttpError(413, 'audio longer than 60 s')
    const cost = costFor(seconds)
    if (account.balance_micros < cost) throw new HttpError(402, 'insufficient balance')
    const { results = [] } = await recognize(audio)
    // 转写成功才扣费；扣费时再核对一次余额，防止并发透支。
    if (!charge(account.id, seconds, cost)) throw new HttpError(402, 'insufficient balance')
    return { results }
  },

  'POST /v1/digest': async (req) => {
    const account = authed(req)
    if (account.balance_micros <= 0) throw new HttpError(402, 'insufficient balance')
    const { system, user, schema } = await readJson(req)
    if (typeof system !== 'string' || typeof user !== 'string' || !schema) throw new HttpError(400, 'system, user, schema required')
    if (user.length > 400_000) throw new HttpError(413, 'transcript too long')
    if (!countDigest(account.id, DIGESTS_PER_DAY)) throw new HttpError(429, 'daily limit reached')
    return { text: await generateJson(system, user, schema) }
  },
}

createServer(async (req, res) => {
  const path = new URL(req.url, 'http://localhost').pathname
  const handler = routes[`${req.method} ${path}`]
  const send = (status, body) => {
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
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
    // 只记路径、状态和耗时，不记内容。
    console.log(`${req.method} ${path} ${res.statusCode} ${Date.now() - started}ms`)
  }
}).listen(PORT, () => {
  console.log(`dayweave cloud on :${PORT}, $${(pricing.priceMicrosPerMinute / 1e6).toFixed(4)}/min`)
})
