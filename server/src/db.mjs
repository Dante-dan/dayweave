// 授权码与账本。用 Node 自带的 SQLite，不需要安装依赖。
// 没有用户体系：授权码本身就是凭证，每个码有固定额度，用完即止。
// 数据库里只存授权码的 SHA-256，不存明文；授权码在本地生成（server/local/，不进仓库），只把哈希导入这里。
import { DatabaseSync } from 'node:sqlite'
import { createHash, createHmac } from 'node:crypto'
import { mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
import { MONTH_MS } from './plans.mjs'

const path = process.env.DB_PATH || 'data/dayweave.db'
mkdirSync(dirname(path), { recursive: true })
export const db = new DatabaseSync(path)
db.exec(`
  PRAGMA journal_mode = WAL;
  DROP TABLE IF EXISTS accounts;
  DROP TABLE IF EXISTS codes;
  CREATE TABLE IF NOT EXISTS licenses (
    hash TEXT PRIMARY KEY,
    label TEXT NOT NULL DEFAULT '',
    granted_micros INTEGER NOT NULL,
    balance_micros INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    first_used_at INTEGER,
    last_used_at INTEGER,
    disabled INTEGER NOT NULL DEFAULT 0
  );
  CREATE TABLE IF NOT EXISTS usage (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    license TEXT NOT NULL,
    at INTEGER NOT NULL,
    kind TEXT NOT NULL,
    seconds INTEGER NOT NULL DEFAULT 0,
    micros INTEGER NOT NULL
  );
  CREATE INDEX IF NOT EXISTS usage_license ON usage(license, at);
  CREATE TABLE IF NOT EXISTS assists (
    license TEXT NOT NULL,
    day TEXT NOT NULL,
    count INTEGER NOT NULL,
    PRIMARY KEY (license, day)
  );
`)
db.exec('DROP TABLE IF EXISTS ledger; DROP TABLE IF EXISTS digests;')

// 订阅：每个商店订阅对应一行 licenses（label 为 sub:<方案>），余额在每个新周期重置为方案额度，expires_at 为当前周期结束时间。
// external 是商店里稳定的标识：App Store 的 originalTransactionId，Google Play 的 purchaseToken。
if (!db.prepare("SELECT 1 FROM pragma_table_info('licenses') WHERE name = 'expires_at'").get()) {
  db.exec('ALTER TABLE licenses ADD COLUMN expires_at INTEGER')
}
// 年付订阅按月发放：next_reset_at 到了就把余额重置为每月额度。
if (!db.prepare("SELECT 1 FROM pragma_table_info('licenses') WHERE name = 'next_reset_at'").get()) {
  db.exec('ALTER TABLE licenses ADD COLUMN next_reset_at INTEGER')
}
// 每个授权码的单价：调价前售出的码沿用当时的价格（$0.0404/分钟），之后的码为空，按当前单价。
if (!db.prepare("SELECT 1 FROM pragma_table_info('licenses') WHERE name = 'price_micros_per_minute'").get()) {
  db.exec('ALTER TABLE licenses ADD COLUMN price_micros_per_minute INTEGER')
  db.exec("UPDATE licenses SET price_micros_per_minute = 40400 WHERE label NOT LIKE 'sub:%'")
}
db.exec(`
  CREATE TABLE IF NOT EXISTS subscriptions (
    store TEXT NOT NULL,
    external TEXT NOT NULL,
    license TEXT NOT NULL,
    product_id TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    environment TEXT NOT NULL DEFAULT '',
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (store, external)
  );
  CREATE INDEX IF NOT EXISTS subscriptions_license ON subscriptions(license);
`)

// 后台需要的账目：每笔用量的 API 成本、模型 token；授权码的备注、末段提示（后台生成的码才有）；每个码用过的设备。
for (const [table, column, type] of [
  ['usage', 'cost_micros', 'INTEGER NOT NULL DEFAULT 0'],
  ['usage', 'tokens_in', 'INTEGER NOT NULL DEFAULT 0'],
  ['usage', 'tokens_out', 'INTEGER NOT NULL DEFAULT 0'],
  ['usage', 'device', 'TEXT'],
  ['licenses', 'note', "TEXT NOT NULL DEFAULT ''"],
  ['licenses', 'hint', "TEXT NOT NULL DEFAULT ''"],
  ['licenses', 'sale_micros', 'INTEGER'],
  ['licenses', 'disabled_at', 'INTEGER'],
]) {
  if (!db.prepare(`SELECT 1 FROM pragma_table_info('${table}') WHERE name = ?`).get(column)) db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${type}`)
}
db.exec(`
  CREATE TABLE IF NOT EXISTS devices (
    license TEXT NOT NULL,
    device TEXT NOT NULL,
    platform TEXT NOT NULL DEFAULT '',
    estimated INTEGER NOT NULL DEFAULT 0,
    first_seen INTEGER NOT NULL,
    last_seen INTEGER NOT NULL,
    requests INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (license, device)
  );
  CREATE INDEX IF NOT EXISTS usage_at ON usage(at);
`)

/** 记下这个授权码在哪台设备上用。新版 App 带安装标识；旧版没有，就用来源和 UA 估一个（estimated）。 */
export function touchDevice(license, device, platform, estimated) {
  const now = Date.now()
  db.prepare(`INSERT INTO devices (license, device, platform, estimated, first_seen, last_seen, requests) VALUES (?, ?, ?, ?, ?, ?, 1)
    ON CONFLICT(license, device) DO UPDATE SET last_seen = excluded.last_seen, platform = excluded.platform, requests = requests + 1`)
    .run(license, device, platform || '', estimated ? 1 : 0, now, now)
}

/** 记一笔不扣费的用量（AI 整理、提醒、对话），只为后台核算成本。 */
export function recordUsage(hash, kind, { seconds = 0, micros = 0, costMicros = 0, tokensIn = 0, tokensOut = 0, device = null } = {}) {
  db.prepare('INSERT INTO usage (license, at, kind, seconds, micros, cost_micros, tokens_in, tokens_out, device) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)')
    .run(hash, Date.now(), kind, seconds, micros, costMicros, tokensIn, tokensOut, device)
}

/** 授权码规范化：去掉空格和连字符、转大写。“dw-abcd efgh” 与 “DWABCDEFGH” 是同一个码。 */
export const normalize = (code) => String(code || '').toUpperCase().replace(/[^0-9A-Z]/g, '')
export const hashOf = (code) => createHash('sha256').update(normalize(code)).digest('hex')

export function licenseFor(code) {
  const normalized = normalize(code)
  if (normalized.length < 16 || normalized.length > 40) return null
  let row = db.prepare('SELECT * FROM licenses WHERE hash = ?').get(hashOf(normalized))
  if (row?.next_reset_at && Date.now() >= row.next_reset_at && Date.now() < (row.expires_at || 0)) {
    // 年付：跨过一个月就重置为每月额度（不结转），下一次重置顺延 30 天
    let next = row.next_reset_at
    while (next <= Date.now()) next += MONTH_MS
    db.prepare('UPDATE licenses SET balance_micros = granted_micros, next_reset_at = ? WHERE hash = ?').run(next, row.hash)
    row = db.prepare('SELECT * FROM licenses WHERE hash = ?').get(row.hash)
  }
  return row && !row.disabled ? row : null
}

/** 扣费并记账。余额不足时返回 false，不扣。 */
export function charge(hash, seconds, micros, costMicros = 0, device = null) {
  db.exec('BEGIN IMMEDIATE')
  try {
    const row = db.prepare('SELECT balance_micros FROM licenses WHERE hash = ?').get(hash)
    if (!row || row.balance_micros < micros) {
      db.exec('ROLLBACK')
      return false
    }
    const now = Date.now()
    db.prepare('UPDATE licenses SET balance_micros = balance_micros - ?, last_used_at = ?, first_used_at = COALESCE(first_used_at, ?) WHERE hash = ?')
      .run(micros, now, now, hash)
    db.prepare('INSERT INTO usage (license, at, kind, seconds, micros, cost_micros, device) VALUES (?, ?, ?, ?, ?, ?, ?)')
      .run(hash, now, 'transcribe', seconds, micros, costMicros, device)
    db.exec('COMMIT')
    return true
  } catch (e) {
    db.exec('ROLLBACK')
    throw e
  }
}

/**
 * 记录一次核验通过的商店订阅，返回 App 用来访问拾日云的凭证（DS-…）。
 * 凭证由服务端密钥对订阅标识做 HMAC 得到：同一订阅在任何设备上恢复购买都得到同一个凭证，库里仍只存哈希。
 * Google Play 升级降级会换新的 purchaseToken 并带上 linkedPurchaseToken，沿用原来的凭证和余额。
 */
export function upsertSubscription(proof, grantMicros, secret) {
  const linked = proof.linked && db.prepare('SELECT license FROM subscriptions WHERE store = ? AND external = ?').get(proof.store, proof.linked)
  const existing = db.prepare('SELECT license, expires_at, product_id FROM subscriptions WHERE store = ? AND external = ?').get(proof.store, proof.external)
  const known = existing?.license || linked?.license
  const code = subscriptionCode(known ? subscriptionRoot(known) : `${proof.store}:${proof.external}`, secret)
  const license = known || hashOf(code)
  const now = Date.now()
  db.exec('BEGIN IMMEDIATE')
  try {
    const row = db.prepare('SELECT expires_at FROM licenses WHERE hash = ?').get(license)
    const label = `sub:${proof.plan}`
    // 年付：本周期内每 30 天重置一次；月付由商店续费触发重置
    const nextReset = proof.period === 'yearly' ? now + MONTH_MS : null
    if (!row) {
      db.prepare('INSERT INTO licenses (hash, label, granted_micros, balance_micros, created_at, expires_at, next_reset_at) VALUES (?, ?, ?, ?, ?, ?, ?)')
        .run(license, label, grantMicros, grantMicros, now, proof.expiresAt, nextReset)
    } else if (proof.expiresAt > (row.expires_at || 0)) {
      // 新的计费周期（续费或换方案）：余额重置为方案额度，不结转。
      db.prepare('UPDATE licenses SET label = ?, granted_micros = ?, balance_micros = ?, expires_at = ?, next_reset_at = ?, disabled = 0 WHERE hash = ?')
        .run(label, grantMicros, grantMicros, proof.expiresAt, nextReset, license)
    }
    db.prepare(`INSERT INTO subscriptions (store, external, license, product_id, expires_at, environment, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(store, external) DO UPDATE SET product_id = excluded.product_id, expires_at = excluded.expires_at, updated_at = excluded.updated_at`)
      .run(proof.store, proof.external, license, proof.productId, proof.expiresAt, proof.environment || '', now)
    db.exec('COMMIT')
  } catch (e) {
    db.exec('ROLLBACK')
    throw e
  }
  return { license, code }
}

/** 由订阅的根标识推出凭证：DS + 24 位 Crockford Base32（120 bit）。 */
export function subscriptionCode(root, secret) {
  const mac = createHmac('sha256', secret).update(root).digest()
  const alphabet = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
  let bits = 0, value = 0, out = ''
  for (const byte of mac) {
    value = (value << 8) | byte
    bits += 8
    while (bits >= 5 && out.length < 24) {
      out += alphabet[(value >>> (bits - 5)) & 31]
      bits -= 5
    }
  }
  return `DS-${out.match(/.{6}/g).join('-')}`
}

/** 订阅凭证对应的根标识（最早的那一笔订阅）。 */
function subscriptionRoot(license) {
  const row = db.prepare('SELECT store, external FROM subscriptions WHERE license = ? ORDER BY updated_at ASC LIMIT 1').get(license)
  return row && `${row.store}:${row.external}`
}

export function summary(license) {
  const month = new Date()
  const since = new Date(month.getFullYear(), month.getMonth(), 1).getTime()
  const used = db.prepare(
    'SELECT COALESCE(SUM(seconds), 0) AS seconds, COALESCE(SUM(micros), 0) AS micros FROM usage WHERE license = ? AND at >= ?',
  ).get(license.hash, since)
  const ledger = db.prepare("SELECT at, kind, seconds, micros FROM usage WHERE license = ? AND kind = 'transcribe' ORDER BY at DESC LIMIT 30").all(license.hash)
    .map((r) => ({ ...r, micros: -r.micros }))
  return {
    label: license.label,
    grantedMicros: license.granted_micros,
    balanceMicros: license.balance_micros,
    expiresAt: license.expires_at || null,
    resetsAt: license.next_reset_at || license.expires_at || null,
    monthSeconds: used.seconds,
    monthMicros: used.micros,
    ledger,
  }
}

/** 每个授权码每天的 AI 调用次数，超过上限时返回 false。 */
export function countAssist(hash, limit) {
  const day = new Date().toISOString().slice(0, 10)
  const row = db.prepare('SELECT count FROM assists WHERE license = ? AND day = ?').get(hash, day)
  if (row && row.count >= limit) return false
  db.prepare('INSERT INTO assists (license, day, count) VALUES (?, ?, 1) ON CONFLICT(license, day) DO UPDATE SET count = count + 1').run(hash, day)
  return true
}

/** 导入授权码（只收哈希）。已存在的跳过，返回新增数量。 */
export function importLicenses(rows) {
  const insert = db.prepare('INSERT OR IGNORE INTO licenses (hash, label, granted_micros, balance_micros, created_at) VALUES (?, ?, ?, ?, ?)')
  let added = 0
  for (const { hash, micros, label } of rows) {
    if (!/^[0-9a-f]{64}$/.test(hash) || !(micros > 0)) continue
    added += Number(insert.run(hash, label || '', micros, micros, Date.now()).changes)
  }
  return added
}
