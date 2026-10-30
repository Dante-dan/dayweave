// 账户、账本、兑换码。用 Node 自带的 SQLite，不需要安装依赖。
import { DatabaseSync } from 'node:sqlite'
import { createHash, randomBytes } from 'node:crypto'
import { mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

const path = process.env.DB_PATH || 'data/dayweave.db'
mkdirSync(dirname(path), { recursive: true })
export const db = new DatabaseSync(path)
db.exec(`
  PRAGMA journal_mode = WAL;
  CREATE TABLE IF NOT EXISTS accounts (
    id TEXT PRIMARY KEY,
    token_hash TEXT UNIQUE NOT NULL,
    balance_micros INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS ledger (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id TEXT NOT NULL,
    at INTEGER NOT NULL,
    kind TEXT NOT NULL,
    seconds INTEGER NOT NULL DEFAULT 0,
    micros INTEGER NOT NULL,
    note TEXT NOT NULL DEFAULT ''
  );
  CREATE INDEX IF NOT EXISTS ledger_account ON ledger(account_id, at);
  CREATE TABLE IF NOT EXISTS codes (
    code TEXT PRIMARY KEY,
    micros INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    redeemed_by TEXT,
    redeemed_at INTEGER
  );
  CREATE TABLE IF NOT EXISTS digests (
    account_id TEXT NOT NULL,
    day TEXT NOT NULL,
    count INTEGER NOT NULL,
    PRIMARY KEY (account_id, day)
  );
`)

export const hash = (token) => createHash('sha256').update(token).digest('hex')
export const newToken = () => randomBytes(32).toString('base64url')

export function createAccount(trialMicros) {
  const token = newToken()
  const id = randomBytes(9).toString('base64url')
  const now = Date.now()
  db.prepare('INSERT INTO accounts (id, token_hash, balance_micros, created_at) VALUES (?, ?, ?, ?)').run(id, hash(token), trialMicros, now)
  if (trialMicros > 0) {
    db.prepare('INSERT INTO ledger (account_id, at, kind, micros, note) VALUES (?, ?, ?, ?, ?)').run(id, now, 'trial', trialMicros, '新用户体验额度')
  }
  return { id, token }
}

export function accountByToken(token) {
  return db.prepare('SELECT * FROM accounts WHERE token_hash = ?').get(hash(token))
}

/** 扣费并记账。余额不足时返回 false，不扣。 */
export function charge(accountId, seconds, micros) {
  db.exec('BEGIN IMMEDIATE')
  try {
    const row = db.prepare('SELECT balance_micros FROM accounts WHERE id = ?').get(accountId)
    if (!row || row.balance_micros < micros) {
      db.exec('ROLLBACK')
      return false
    }
    db.prepare('UPDATE accounts SET balance_micros = balance_micros - ? WHERE id = ?').run(micros, accountId)
    db.prepare('INSERT INTO ledger (account_id, at, kind, seconds, micros) VALUES (?, ?, ?, ?, ?)').run(accountId, Date.now(), 'transcribe', seconds, -micros)
    db.exec('COMMIT')
    return true
  } catch (e) {
    db.exec('ROLLBACK')
    throw e
  }
}

/** 兑换充值码。返回 'ok' | 'missing' | 'used'。 */
export function redeem(accountId, code) {
  db.exec('BEGIN IMMEDIATE')
  try {
    const row = db.prepare('SELECT * FROM codes WHERE code = ?').get(code)
    if (!row) {
      db.exec('ROLLBACK')
      return 'missing'
    }
    if (row.redeemed_by) {
      db.exec('ROLLBACK')
      return 'used'
    }
    const now = Date.now()
    db.prepare('UPDATE codes SET redeemed_by = ?, redeemed_at = ? WHERE code = ?').run(accountId, now, code)
    db.prepare('UPDATE accounts SET balance_micros = balance_micros + ? WHERE id = ?').run(row.micros, accountId)
    db.prepare('INSERT INTO ledger (account_id, at, kind, micros, note) VALUES (?, ?, ?, ?, ?)').run(accountId, now, 'redeem', row.micros, code.slice(0, 4) + '…')
    db.exec('COMMIT')
    return 'ok'
  } catch (e) {
    db.exec('ROLLBACK')
    throw e
  }
}

export function summary(accountId) {
  const account = db.prepare('SELECT balance_micros FROM accounts WHERE id = ?').get(accountId)
  const month = new Date()
  const since = new Date(month.getFullYear(), month.getMonth(), 1).getTime()
  const used = db.prepare(
    "SELECT COALESCE(SUM(seconds), 0) AS seconds, COALESCE(-SUM(micros), 0) AS micros FROM ledger WHERE account_id = ? AND kind = 'transcribe' AND at >= ?",
  ).get(accountId, since)
  const ledger = db.prepare('SELECT at, kind, seconds, micros, note FROM ledger WHERE account_id = ? ORDER BY at DESC LIMIT 30').all(accountId)
  return { balanceMicros: account.balance_micros, monthSeconds: used.seconds, monthMicros: used.micros, ledger }
}

/** 每个账户每天的整理次数，超过上限时返回 false。 */
export function countDigest(accountId, limit) {
  const day = new Date().toISOString().slice(0, 10)
  const row = db.prepare('SELECT count FROM digests WHERE account_id = ? AND day = ?').get(accountId, day)
  if (row && row.count >= limit) return false
  db.prepare('INSERT INTO digests (account_id, day, count) VALUES (?, ?, 1) ON CONFLICT(account_id, day) DO UPDATE SET count = count + 1').run(accountId, day)
  return true
}

export function createCodes(micros, count) {
  const codes = []
  for (let i = 0; i < count; i++) {
    const code = randomBytes(8).toString('hex').toUpperCase().match(/.{4}/g).join('-')
    db.prepare('INSERT INTO codes (code, micros, created_at) VALUES (?, ?, ?)').run(code, micros, Date.now())
    codes.push(code)
  }
  return codes
}
