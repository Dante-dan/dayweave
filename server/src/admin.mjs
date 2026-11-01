// 后台接口：只给 dayweave.duanjl.com/admin 的后台服务调用（Authorization: Bearer <ADMIN_TOKEN>）。
// 授权码明文从不经过这里：后台在浏览器里生成，只把哈希交过来。
import { timingSafeEqual, createHash } from 'node:crypto'
import { db } from './db.mjs'

const TOKEN = process.env.ADMIN_TOKEN || ''
const DAY = 86_400_000

export function adminAllowed(req) {
  if (TOKEN.length < 32) return false
  const given = (req.headers.authorization || '').replace(/^Bearer\s+/i, '')
  const a = createHash('sha256').update(given).digest()
  const b = createHash('sha256').update(TOKEN).digest()
  return timingSafeEqual(a, b)
}

const totals = (where = '', ...args) => db.prepare(`
  SELECT COALESCE(SUM(micros), 0) AS charged, COALESCE(SUM(cost_micros), 0) AS cost,
         COALESCE(SUM(seconds), 0) AS seconds, COUNT(*) AS calls,
         COALESCE(SUM(tokens_in), 0) AS tokensIn, COALESCE(SUM(tokens_out), 0) AS tokensOut
  FROM usage ${where}`).get(...args)

const kindOf = (label) => (label.startsWith('sub:') ? 'subscription' : 'code')

export function overview() {
  const now = Date.now()
  const today = new Date(); today.setHours(0, 0, 0, 0)
  const month = new Date(today.getFullYear(), today.getMonth(), 1).getTime()
  const since = today.getTime() - 29 * DAY
  const days = db.prepare(`
    SELECT strftime('%Y-%m-%d', at / 1000, 'unixepoch', 'localtime') AS day,
           SUM(micros) AS charged, SUM(cost_micros) AS cost, SUM(seconds) AS seconds, COUNT(*) AS calls
    FROM usage WHERE at >= ? GROUP BY day ORDER BY day`).all(since)
  const byKind = db.prepare(`
    SELECT kind, SUM(micros) AS charged, SUM(cost_micros) AS cost, SUM(seconds) AS seconds, COUNT(*) AS calls,
           SUM(tokens_in) AS tokensIn, SUM(tokens_out) AS tokensOut
    FROM usage WHERE at >= ? GROUP BY kind`).all(month)
  const licenses = db.prepare(`
    SELECT COUNT(*) AS total, SUM(disabled) AS disabled,
           SUM(CASE WHEN label LIKE 'sub:%' THEN 1 ELSE 0 END) AS subscriptions,
           SUM(CASE WHEN last_used_at >= ? THEN 1 ELSE 0 END) AS active7d,
           SUM(CASE WHEN label NOT LIKE 'sub:%' THEN COALESCE(sale_micros, granted_micros) ELSE 0 END) AS codeSales,
           SUM(CASE WHEN label NOT LIKE 'sub:%' AND disabled = 0 THEN balance_micros ELSE 0 END) AS outstanding
    FROM licenses`).get(now - 7 * DAY)
  const devices = db.prepare('SELECT COUNT(*) AS n FROM devices WHERE last_seen >= ?').get(now - 30 * DAY).n
  return {
    now,
    today: totals('WHERE at >= ?', today.getTime()),
    month: totals('WHERE at >= ?', month),
    all: totals(),
    days, byKind, licenses, devices30d: devices,
  }
}

export function listLicenses() {
  const rows = db.prepare(`
    SELECT l.hash, l.label, l.note, l.hint, l.granted_micros, l.balance_micros, l.price_micros_per_minute, l.sale_micros,
           l.created_at, l.first_used_at, l.last_used_at, l.disabled, l.disabled_at, l.expires_at,
           COALESCE(u.charged, 0) AS charged, COALESCE(u.cost, 0) AS cost, COALESCE(u.seconds, 0) AS seconds, COALESCE(u.calls, 0) AS calls,
           COALESCE(d.n, 0) AS devices, COALESCE(d.active, 0) AS activeDevices
    FROM licenses l
    LEFT JOIN (SELECT license, SUM(micros) AS charged, SUM(cost_micros) AS cost, SUM(seconds) AS seconds, COUNT(*) AS calls FROM usage GROUP BY license) u ON u.license = l.hash
    LEFT JOIN (SELECT license, COUNT(*) AS n, SUM(CASE WHEN last_seen >= ? THEN 1 ELSE 0 END) AS active FROM devices GROUP BY license) d ON d.license = l.hash
    ORDER BY COALESCE(l.last_used_at, l.created_at) DESC`).all(Date.now() - 30 * DAY)
  return { licenses: rows.map((r) => ({ ...r, kind: kindOf(r.label) })) }
}

export function licenseDetail(hash) {
  const license = db.prepare('SELECT * FROM licenses WHERE hash = ?').get(hash)
  if (!license) return null
  const devices = db.prepare('SELECT device, platform, estimated, first_seen, last_seen, requests FROM devices WHERE license = ? ORDER BY last_seen DESC').all(hash)
    .map((d) => ({ ...d, device: d.device.slice(0, 10) }))
  const usage = db.prepare('SELECT at, kind, seconds, micros, cost_micros, tokens_in, tokens_out FROM usage WHERE license = ? ORDER BY at DESC LIMIT 100').all(hash)
  const days = db.prepare(`
    SELECT strftime('%Y-%m-%d', at / 1000, 'unixepoch', 'localtime') AS day, SUM(micros) AS charged, SUM(cost_micros) AS cost, SUM(seconds) AS seconds
    FROM usage WHERE license = ? AND at >= ? GROUP BY day ORDER BY day`).all(hash, Date.now() - 30 * DAY)
  const subscription = db.prepare('SELECT store, product_id, expires_at, environment FROM subscriptions WHERE license = ? ORDER BY updated_at DESC LIMIT 1').get(hash)
  return { license: { ...license, kind: kindOf(license.label) }, totals: totals('WHERE license = ?', hash), devices, usage, days, subscription }
}

const positive = (v) => Number.isFinite(v) && v > 0

/** 导入后台生成的授权码：每个都带自己的额度、单价、售价和备注。 */
export function createLicenses({ codes, grantMicros, priceMicrosPerMinute, saleMicros, label, note }) {
  if (!Array.isArray(codes) || !codes.length || codes.length > 200) throw new Error('codes required (1-200)')
  if (!positive(grantMicros)) throw new Error('grant required')
  if (priceMicrosPerMinute != null && !positive(priceMicrosPerMinute)) throw new Error('invalid price')
  const insert = db.prepare(`INSERT OR IGNORE INTO licenses (hash, label, note, hint, granted_micros, balance_micros, price_micros_per_minute, sale_micros, created_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`)
  let added = 0
  db.exec('BEGIN IMMEDIATE')
  try {
    for (const { hash, hint } of codes) {
      if (!/^[0-9a-f]{64}$/.test(hash)) throw new Error('invalid hash')
      added += Number(insert.run(hash, String(label || '').slice(0, 80), String(note || '').slice(0, 500), String(hint || '').slice(0, 8),
        Math.round(grantMicros), Math.round(grantMicros), priceMicrosPerMinute ? Math.round(priceMicrosPerMinute) : null,
        positive(saleMicros) ? Math.round(saleMicros) : null, Date.now()).changes)
    }
    db.exec('COMMIT')
  } catch (e) {
    db.exec('ROLLBACK')
    throw e
  }
  return { added }
}

/** 修改授权码：废弃/恢复、改备注、改单价、追加或设定额度。 */
export function updateLicense({ hash, disabled, label, note, priceMicrosPerMinute, addMicros, balanceMicros, saleMicros }) {
  const row = db.prepare('SELECT * FROM licenses WHERE hash = ?').get(hash)
  if (!row) return null
  const set = []
  const args = []
  if (disabled != null) { set.push('disabled = ?', 'disabled_at = ?'); args.push(disabled ? 1 : 0, disabled ? Date.now() : null) }
  if (label != null) { set.push('label = ?'); args.push(String(label).slice(0, 80)) }
  if (note != null) { set.push('note = ?'); args.push(String(note).slice(0, 500)) }
  if (priceMicrosPerMinute !== undefined) {
    if (priceMicrosPerMinute !== null && !positive(priceMicrosPerMinute)) throw new Error('invalid price')
    set.push('price_micros_per_minute = ?'); args.push(priceMicrosPerMinute === null ? null : Math.round(priceMicrosPerMinute))
  }
  if (saleMicros !== undefined) { set.push('sale_micros = ?'); args.push(positive(saleMicros) ? Math.round(saleMicros) : null) }
  if (Number.isFinite(addMicros) && addMicros !== 0) {
    set.push('granted_micros = MAX(0, granted_micros + ?)', 'balance_micros = MAX(0, balance_micros + ?)'); args.push(Math.round(addMicros), Math.round(addMicros))
  }
  if (Number.isFinite(balanceMicros) && balanceMicros >= 0) { set.push('balance_micros = ?'); args.push(Math.round(balanceMicros)) }
  if (set.length) db.prepare(`UPDATE licenses SET ${set.join(', ')} WHERE hash = ?`).run(...args, hash)
  return licenseDetail(hash)
}
