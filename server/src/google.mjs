// 用我们自己的服务账号调用 Google：Speech-to-Text v2（Chirp 3）和 Vertex AI（Gemini）。
import { createSign } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { homedir } from 'node:os'

const file = (process.env.GOOGLE_SERVICE_ACCOUNT_FILE || '').replace(/^~/, homedir())
const sa = JSON.parse(readFileSync(file, 'utf8'))
export const project = sa.project_id
export const digestModel = process.env.DIGEST_MODEL || 'gemini-2.5-flash'

let cached = { token: '', expiresAt: 0 }

async function token() {
  const now = Date.now()
  if (cached.token && now < cached.expiresAt - 5 * 60_000) return cached.token
  const b64 = (v) => Buffer.from(typeof v === 'string' ? v : JSON.stringify(v)).toString('base64url')
  const iat = Math.floor(now / 1000)
  const unsigned = `${b64({ alg: 'RS256', typ: 'JWT' })}.${b64({
    iss: sa.client_email, scope: 'https://www.googleapis.com/auth/cloud-platform', aud: sa.token_uri, iat, exp: iat + 3600,
  })}`
  const signature = createSign('RSA-SHA256').update(unsigned).sign(sa.private_key).toString('base64url')
  const res = await fetch(sa.token_uri, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${unsigned}.${signature}` }),
  })
  if (!res.ok) throw new UpstreamError(res.status, await res.text())
  const json = await res.json()
  cached = { token: json.access_token, expiresAt: now + json.expires_in * 1000 }
  return cached.token
}

export class UpstreamError extends Error {
  constructor(status, body) {
    super(`upstream ${status}: ${String(body).slice(0, 300)}`)
    this.status = status
  }
}

async function post(url, body) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await token()}` },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(180_000),
  })
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

export async function generateJson(system, user, schema) {
  const json = await post(
    `https://aiplatform.googleapis.com/v1/projects/${project}/locations/global/publishers/google/models/${digestModel}:generateContent`,
    {
      systemInstruction: { parts: [{ text: system }] },
      contents: [{ role: 'user', parts: [{ text: user }] }],
      generationConfig: { temperature: 0.3, responseMimeType: 'application/json', responseSchema: schema },
    },
  )
  return json.candidates[0].content.parts.map((p) => p.text || '').join('')
}
