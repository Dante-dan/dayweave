// 授权码与账本。用 Node 自带的 SQLite，不需要安装依赖。
// 没有用户体系：授权码本身就是凭证，每个码有固定额度，用完即止。
// 数据库里只存授权码的 SHA-256，不存明文；授权码在本地生成（server/local/，不进仓库），只把哈希导入这里。
import { DatabaseSync } from 'node:sqlite'
import { createHash } from 'node:crypto'
import { mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

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

/** 授权码规范化：去掉空格和连字符、转大写。“dw-abcd efgh” 与 “DWABCDEFGH” 是同一个码。 */
export const normalize = (code) => String(code || '').toUpperCase().replace(/[^0-9A-Z]/g, '')
export const hashOf = (code) => createHash('sha256').update(normalize(code)).digest('hex')

export function licenseFor(code) {
  const normalized = normalize(code)
  if (normalized.length < 16 || normalized.length > 40) return null
  const row = db.prepare('SELECT * FROM licenses WHERE hash = ?').get(hashOf(normalized))
  return row && !row.disabled ? row : null
}

/** 扣费并记账。余额不足时返回 false，不扣。 */
export function charge(hash, seconds, micros) {
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
    db.prepare('INSERT INTO usage (license, at, kind, seconds, micros) VALUES (?, ?, ?, ?, ?)').run(hash, now, 'transcribe', seconds, micros)
    db.exec('COMMIT')
    return true
  } catch (e) {
    db.exec('ROLLBACK')
    throw e
  }
}

export function summary(license) {
  const month = new Date()
  const since = new Date(month.getFullYear(), month.getMonth(), 1).getTime()
  const used = db.prepare(
    'SELECT COALESCE(SUM(seconds), 0) AS seconds, COALESCE(SUM(micros), 0) AS micros FROM usage WHERE license = ? AND at >= ?',
  ).get(license.hash, since)
  const ledger = db.prepare('SELECT at, kind, seconds, micros FROM usage WHERE license = ? ORDER BY at DESC LIMIT 30').all(license.hash)
    .map((r) => ({ ...r, micros: -r.micros }))
  return {
    label: license.label,
    grantedMicros: license.granted_micros,
    balanceMicros: license.balance_micros,
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
