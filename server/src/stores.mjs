// 核验应用商店的订阅凭证。
// - App Store：StoreKit 2 的签名交易（JWS）离线核验：x5c 证书链必须到 Apple Root CA - G3（下方固定），再用叶子证书验签。
// - Google Play：用服务账号调用 Android Publisher API 查询购买凭证，并在需要时确认（acknowledge）。
// 返回统一结构：{ store, external, productId, expiresAt, active, linked }，external 是该订阅在商店里稳定不变的标识。
import { X509Certificate, createPublicKey, verify } from 'node:crypto'
import { accessToken } from './google.mjs'

export const BUNDLE_ID = process.env.APPLE_BUNDLE_ID || 'app.dayweave'
export const PACKAGE_NAME = process.env.PLAY_PACKAGE_NAME || 'app.dayweave'

export class ProofError extends Error {}

// https://www.apple.com/certificateauthority/AppleRootCA-G3.cer
// SHA-256 63:34:3A:BF:B8:9A:6A:03:EB:B5:7E:9B:3F:5F:A7:BE:7C:4F:5C:75:6F:30:17:B3:A8:C4:88:C3:65:3E:91:79
const APPLE_ROOT_G3 = new X509Certificate(`-----BEGIN CERTIFICATE-----
MIICQzCCAcmgAwIBAgIILcX8iNLFS5UwCgYIKoZIzj0EAwMwZzEbMBkGA1UEAwwS
QXBwbGUgUm9vdCBDQSAtIEczMSYwJAYDVQQLDB1BcHBsZSBDZXJ0aWZpY2F0aW9u
IEF1dGhvcml0eTETMBEGA1UECgwKQXBwbGUgSW5jLjELMAkGA1UEBhMCVVMwHhcN
MTQwNDMwMTgxOTA2WhcNMzkwNDMwMTgxOTA2WjBnMRswGQYDVQQDDBJBcHBsZSBS
b290IENBIC0gRzMxJjAkBgNVBAsMHUFwcGxlIENlcnRpZmljYXRpb24gQXV0aG9y
aXR5MRMwEQYDVQQKDApBcHBsZSBJbmMuMQswCQYDVQQGEwJVUzB2MBAGByqGSM49
AgEGBSuBBAAiA2IABJjpLz1AcqTtkyJygRMc3RCV8cWjTnHcFBbZDuWmBSp3ZHtf
TjjTuxxEtX/1H7YyYl3J6YRbTzBPEVoA/VhYDKX1DyxNB0cTddqXl5dvMVztK517
IDvYuVTZXpmkOlEKMaNCMEAwHQYDVR0OBBYEFLuw3qFYM4iapIqZ3r6966/ayySr
MA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQDAgEGMAoGCCqGSM49BAMDA2gA
MGUCMQCD6cHEFl4aXTQY2e3v9GwOAEZLuN+yRhHFD/3meoyhpmvOwgPUnPWTxnS4
at+qIxUCMG1mihDK1A3UT82NQz60imOlM27jbdoXt2QfyFMm+YhidDkLF1vLUagM
6BgD56KyKA==
-----END CERTIFICATE-----`)

// 叶子证书必须带 Apple 的收据签名扩展 1.2.840.113635.100.6.11.1，中间证书带 1.2.840.113635.100.6.2.1。
const oidBytes = (oid) => {
  const [a, b, ...rest] = oid.split('.').map(Number)
  const out = [40 * a + b]
  for (const n of rest) {
    const parts = []
    let v = n
    do { parts.unshift(v & 0x7f); v >>>= 7 } while (v)
    out.push(...parts.map((p, i) => (i < parts.length - 1 ? p | 0x80 : p)))
  }
  return Buffer.from([0x06, out.length, ...out])
}
const LEAF_OID = oidBytes('1.2.840.113635.100.6.11.1')
const INTERMEDIATE_OID = oidBytes('1.2.840.113635.100.6.2.1')

function verifyAppleJws(jws) {
  const parts = String(jws || '').split('.')
  if (parts.length !== 3) throw new ProofError('malformed transaction')
  const header = JSON.parse(Buffer.from(parts[0], 'base64url').toString('utf8'))
  if (header.alg !== 'ES256' || !Array.isArray(header.x5c) || header.x5c.length !== 3) throw new ProofError('unexpected signature')
  const [leaf, intermediate, root] = header.x5c.map((c) => new X509Certificate(Buffer.from(c, 'base64')))
  const now = new Date()
  const valid = (c) => new Date(c.validFrom) <= now && now <= new Date(c.validTo)
  if (root.fingerprint256 !== APPLE_ROOT_G3.fingerprint256) throw new ProofError('untrusted root')
  if (!intermediate.verify(APPLE_ROOT_G3.publicKey) || !leaf.verify(intermediate.publicKey)) throw new ProofError('broken chain')
  if (!valid(leaf) || !valid(intermediate)) throw new ProofError('expired certificate')
  if (!leaf.raw.includes(LEAF_OID) || !intermediate.raw.includes(INTERMEDIATE_OID)) throw new ProofError('not an App Store certificate')
  const ok = verify('sha256', Buffer.from(`${parts[0]}.${parts[1]}`), { key: createPublicKey(leaf.publicKey), dsaEncoding: 'ieee-p1363' }, Buffer.from(parts[2], 'base64url'))
  if (!ok) throw new ProofError('bad signature')
  return JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'))
}

export function verifyApple(signedTransaction) {
  let tx
  try {
    tx = verifyAppleJws(signedTransaction)
  } catch (e) {
    // 解析失败（坏的 base64、JSON、证书）一律当作无效凭证
    throw e instanceof ProofError ? e : new ProofError('malformed transaction')
  }
  if (tx.bundleId !== BUNDLE_ID) throw new ProofError('wrong app')
  if (tx.type !== 'Auto-Renewable Subscription') throw new ProofError('not a subscription')
  const expiresAt = Number(tx.expiresDate || 0)
  return {
    store: 'apple',
    external: String(tx.originalTransactionId),
    productId: tx.productId,
    expiresAt,
    active: !tx.revocationDate && expiresAt > Date.now(),
    environment: tx.environment,
  }
}

const PLAY = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE_NAME}/purchases`
const PLAY_SCOPE = 'https://www.googleapis.com/auth/androidpublisher'
const ACTIVE_STATES = new Set(['SUBSCRIPTION_STATE_ACTIVE', 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD', 'SUBSCRIPTION_STATE_CANCELED'])

export async function verifyGoogle(purchaseToken) {
  if (typeof purchaseToken !== 'string' || purchaseToken.length < 20 || purchaseToken.length > 4096) throw new ProofError('malformed token')
  const auth = { Authorization: `Bearer ${await accessToken(PLAY_SCOPE, 'play')}` }
  const res = await fetch(`${PLAY}/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`, { headers: auth, signal: AbortSignal.timeout(20_000) })
  if (res.status === 404 || res.status === 400 || res.status === 410) throw new ProofError('unknown purchase')
  if (!res.ok) throw new Error(`play ${res.status}`)
  const sub = await res.json()
  // 一笔订阅可能有多个商品行（升级降级时），取到期最晚的那一行。
  const line = [...(sub.lineItems || [])].sort((a, b) => Date.parse(b.expiryTime) - Date.parse(a.expiryTime))[0]
  if (!line) throw new ProofError('empty purchase')
  const expiresAt = Date.parse(line.expiryTime)
  const active = ACTIVE_STATES.has(sub.subscriptionState) && expiresAt > Date.now()
  // 三天内不确认会被 Google 自动退款，服务端核验通过就确认。
  if (active && sub.acknowledgementState === 'ACKNOWLEDGEMENT_STATE_PENDING') {
    await fetch(`${PLAY}/subscriptions/${encodeURIComponent(line.productId)}/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`, {
      method: 'POST', headers: { ...auth, 'Content-Type': 'application/json' }, body: '{}', signal: AbortSignal.timeout(20_000),
    }).catch((e) => console.error('play acknowledge', e.message))
  }
  return {
    store: 'google',
    external: purchaseToken,
    linked: sub.linkedPurchaseToken || null,
    productId: line.productId,
    expiresAt,
    active,
    environment: sub.testPurchase ? 'Sandbox' : 'Production',
  }
}
