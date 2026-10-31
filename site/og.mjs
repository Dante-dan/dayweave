// 渲染社交分享图：node site/og.mjs（需要本机 Chrome 和 playwright-core）
import { chromium } from 'playwright-core'
import { fileURLToPath } from 'node:url'
const here = (f) => fileURLToPath(new URL(f, import.meta.url))
const b = await chromium.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' })
const p = await b.newPage({ viewport: { width: 1200, height: 630 } })
await p.goto('file://' + here('og.html')); await p.waitForTimeout(1200)
await p.screenshot({ path: here('public/og.png') })
await b.close()
