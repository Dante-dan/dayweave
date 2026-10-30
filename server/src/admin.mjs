// 运营用的命令行：生成充值码、查看账户。
//   node --env-file=.env src/admin.mjs codes --usd 5 --count 3
//   node --env-file=.env src/admin.mjs accounts
import { createCodes, db } from './db.mjs'
import { pricing } from './pricing.mjs'

const [command, ...rest] = process.argv.slice(2)
const arg = (name, fallback) => {
  const i = rest.indexOf(`--${name}`)
  return i >= 0 ? rest[i + 1] : fallback
}

if (command === 'codes') {
  const usd = Number(arg('usd', '5'))
  const count = Number(arg('count', '1'))
  const micros = Math.round(usd * 1e6)
  const minutes = Math.floor(micros / pricing.priceMicrosPerMinute)
  for (const code of createCodes(micros, count)) console.log(`${code}  $${usd}  ≈ ${minutes} 分钟`)
} else if (command === 'accounts') {
  for (const a of db.prepare('SELECT id, balance_micros, created_at FROM accounts ORDER BY created_at DESC LIMIT 50').all()) {
    console.log(a.id, `$${(a.balance_micros / 1e6).toFixed(4)}`, new Date(a.created_at).toISOString())
  }
} else {
  console.log('用法：admin.mjs codes --usd 5 --count 3 | accounts')
}
