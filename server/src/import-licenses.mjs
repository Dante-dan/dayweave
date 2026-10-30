// 导入授权码哈希（不接收明文）。每行：<sha256>,<额度 micro-USD>,<备注>
//   本地：node server/local/issue-codes.mjs --count 10 --usd 5 | ssh us-xl 'docker exec -i dayweave-cloud node src/import-licenses.mjs'
import { importLicenses } from './db.mjs'

let input = ''
for await (const chunk of process.stdin) input += chunk
const rows = input.split('\n').map((l) => l.trim()).filter(Boolean).map((l) => {
  const [hash, micros, label] = l.split(',')
  return { hash, micros: Number(micros), label }
})
console.log(`imported ${importLicenses(rows)} of ${rows.length}`)
