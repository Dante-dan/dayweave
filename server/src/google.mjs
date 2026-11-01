// 用我们自己的服务账号调用 Google：Speech-to-Text v2（Chirp 3）和 Vertex AI（Gemini）。
import { createSign } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { homedir } from 'node:os'

const file = (process.env.GOOGLE_SERVICE_ACCOUNT_FILE || '').replace(/^~/, homedir())
const sa = JSON.parse(readFileSync(file, 'utf8'))
export const project = sa.project_id
export const digestModel = process.env.DIGEST_MODEL || 'gemini-3.6-flash'

// Google Play 的订阅核验可以用单独的服务账号（在 Play 管理中心授权）；没配就用同一个。
const playFile = (process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_FILE || '').replace(/^~/, homedir())
const accounts = { cloud: sa, play: playFile ? JSON.parse(readFileSync(playFile, 'utf8')) : sa }
const cached = new Map()

export async function accessToken(scope = 'https://www.googleapis.com/auth/cloud-platform', which = 'cloud') {
  const now = Date.now()
  const key = `${which} ${scope}`
  const hit = cached.get(key)
  if (hit && now < hit.expiresAt - 5 * 60_000) return hit.token
  const account = accounts[which]
  const b64 = (v) => Buffer.from(typeof v === 'string' ? v : JSON.stringify(v)).toString('base64url')
  const iat = Math.floor(now / 1000)
  const unsigned = `${b64({ alg: 'RS256', typ: 'JWT' })}.${b64({
    iss: account.client_email, scope, aud: account.token_uri, iat, exp: iat + 3600,
  })}`
  const signature = createSign('RSA-SHA256').update(unsigned).sign(account.private_key).toString('base64url')
  const res = await fetch(account.token_uri, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${unsigned}.${signature}` }),
  })
  if (!res.ok) throw new UpstreamError(res.status, await res.text())
  const json = await res.json()
  cached.set(key, { token: json.access_token, expiresAt: now + json.expires_in * 1000 })
  return json.access_token
}

export class UpstreamError extends Error {
  constructor(status, body) {
    super(`upstream ${status}: ${String(body).slice(0, 300)}`)
    this.status = status
  }
}

async function post(url, body, attempt = 0) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await accessToken()}` },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(90_000), // 低于 Cloudflare 的 100 秒上限，超时能干净地报错,
  })
  // 配额紧张时（429）稍等再试一次。
  if (res.status === 429 && attempt < 2) {
    await new Promise((r) => setTimeout(r, 2000 * (attempt + 1)))
    return post(url, body, attempt + 1)
  }
  if (!res.ok) throw new UpstreamError(res.status, await res.text())
  return res.json()
}

/** 与 App 自带密钥时完全相同的识别参数：中英双语、说话人分段、词级时间戳。 */
export function recognize(audioBase64) {
  return post(`https://us-speech.googleapis.com/v2/projects/${project}/locations/us/recognizers/_:recognize`, {
    config: {
      explicitDecodingConfig: { encoding: 'LINEAR16', sampleRateHertz: 16000, audioChannelCount: 1 },
      model: 'chirp_3',
      languageCodes: ['cmn-Hans-CN', 'en-US'],
      features: { enableAutomaticPunctuation: true, enableWordTimeOffsets: true, diarizationConfig: {} },
    },
    content: audioBase64,
  })
}

export async function generateJson(system, user, schema, maxOutputTokens = 4096) {
  const json = await post(
    `https://aiplatform.googleapis.com/v1/projects/${project}/locations/global/publishers/google/models/${digestModel}:generateContent`,
    {
      systemInstruction: { parts: [{ text: system }] },
      contents: [{ role: 'user', parts: [{ text: user }] }],
      generationConfig: { temperature: 0.3, maxOutputTokens, responseMimeType: 'application/json', responseSchema: schema },
    },
  )
  const candidate = json.candidates?.[0]
  // 输出被截断多半是模型陷入了重复，宁可报错重试，也不把半截 JSON 交给 App
  if (!candidate || candidate.finishReason === 'MAX_TOKENS') throw new UpstreamError(502, `generation ${candidate?.finishReason || 'empty'}`)
  const usage = json.usageMetadata || {}
  return {
    text: candidate.content.parts.map((p) => p.text || '').join(''),
    tokensIn: usage.promptTokenCount || 0,
    // 思考 token 也按输出计费
    tokensOut: (usage.candidatesTokenCount || 0) + (usage.thoughtsTokenCount || 0),
  }
}
