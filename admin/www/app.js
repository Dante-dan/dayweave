// 拾日后台前端。没有依赖；授权码在这里生成，明文不发给任何服务器。
'use strict'

const $ = (s, el = document) => el.querySelector(s)
const $$ = (s, el = document) => [...el.querySelectorAll(s)]
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
const usd = (micros, digits = 2) => (micros == null ? '—' : `$${(micros / 1e6).toFixed(digits)}`)
const mins = (seconds) => (seconds >= 3600 ? `${(seconds / 3600).toFixed(1)} 小时` : `${Math.round(seconds / 60)} 分钟`)
const when = (ms) => (ms ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : '—')
const ago = (ms) => {
  if (!ms) return '从未'
  const s = (Date.now() - ms) / 1000
  if (s < 60) return '刚刚'
  if (s < 3600) return `${Math.floor(s / 60)} 分钟前`
  if (s < 86400) return `${Math.floor(s / 3600)} 小时前`
  return `${Math.floor(s / 86400)} 天前`
}
const profitClass = (v) => (v > 0 ? 'good' : v < 0 ? 'bad' : '')
const KIND = { transcribe: '转写', digest: '每日整理', agent: '主动提醒', chat: '助手对话' }

async function api(path, body) {
  const res = await fetch(`/admin/api/${path}`, {
    method: body ? 'POST' : 'GET',
    headers: { 'X-Dayweave-Admin': '1', 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
    credentials: 'same-origin',
  })
  const data = await res.json().catch(() => ({}))
  if (res.status === 401 && path !== 'login') { showLogin(); throw new Error('请重新登录') }
  if (!res.ok) { const e = new Error(data.error || `HTTP ${res.status}`); e.data = data; e.status = res.status; throw e }
  return data
}

// ---------- 登录 ----------

function showLogin() { $('#app').hidden = true; $('#login').hidden = false; $('#password').focus() }
function showApp() { $('#login').hidden = true; $('#app').hidden = false; loadOverview() }

let lockTimer
$('#login-form').addEventListener('submit', async (e) => {
  e.preventDefault()
  const err = $('#login-error')
  err.textContent = ''
  try {
    await api('login', { password: $('#password').value })
    $('#password').value = ''
    showApp()
  } catch (ex) {
    const wait = ex.data?.retryAfter || 0
    if (ex.status === 429 || wait) {
      clearInterval(lockTimer)
      let left = wait
      const tick = () => {
        err.textContent = left > 0 ? `尝试过多，请 ${left >= 60 ? `${Math.ceil(left / 60)} 分钟` : `${left} 秒`}后再试` : ''
        if (left-- <= 0) clearInterval(lockTimer)
      }
      tick()
      lockTimer = setInterval(tick, 1000)
    } else err.textContent = '密码不对'
  }
})
$('#logout').addEventListener('click', async () => { await api('logout', {}).catch(() => {}); showLogin() })

// ---------- 标签页 ----------

$$('.tabs button').forEach((b) => b.addEventListener('click', () => {
  $$('.tabs button').forEach((x) => x.classList.toggle('active', x === b))
  $$('.tab').forEach((t) => { t.hidden = t.id !== `tab-${b.dataset.tab}` })
  if (b.dataset.tab === 'overview') loadOverview()
  if (b.dataset.tab === 'licenses') loadLicenses()
}))

// ---------- 概览 ----------

function card(k, v, s = '', cls = '') {
  return `<div class="glass card"><div class="k">${esc(k)}</div><div class="v ${cls}">${v}</div><div class="s">${s}</div></div>`
}

async function loadOverview() {
  const o = await api('overview')
  const p = (t) => t.charged - t.cost
  const margin = (t) => (t.charged ? `毛利率 ${Math.round((p(t) / t.charged) * 100)}%` : '')
  const L = o.licenses
  $('#cards').innerHTML = [
    card('今日 · 用户计费', usd(o.today.charged), `成本 ${usd(o.today.cost)} · ${mins(o.today.seconds)} 人声`),
    card('今日 · 毛利', usd(p(o.today)), margin(o.today), profitClass(p(o.today))),
    card('本月 · 用户计费', usd(o.month.charged), `成本 ${usd(o.month.cost)} · ${mins(o.month.seconds)} 人声`),
    card('本月 · 毛利', usd(p(o.month)), margin(o.month), profitClass(p(o.month))),
    card('累计 · 用户计费', usd(o.all.charged), `成本 ${usd(o.all.cost)} · 毛利 ${usd(p(o.all))}`),
    card('授权码销售额', usd(L.codeSales || 0), `未用余额 ${usd(L.outstanding || 0)}`),
    card('授权码', `${L.total || 0}`, `订阅 ${L.subscriptions || 0} · 已废弃 ${L.disabled || 0} · 7 天活跃 ${L.active7d || 0}`),
    card('30 天活跃设备', `${o.devices30d}`, `本月 AI 调用 ${o.month.calls - (o.byKind.find((k) => k.kind === 'transcribe')?.calls || 0)} 次`),
  ].join('')
  drawChart(o.days)
  $('#by-kind').innerHTML = `<tr><th>用途</th><th class="num">次数</th><th class="num">人声</th><th class="num">输入 token</th><th class="num">输出 token</th><th class="num">计费</th><th class="num">成本</th><th class="num">毛利</th></tr>` +
    (o.byKind.length ? o.byKind.map((k) => `<tr><td>${esc(KIND[k.kind] || k.kind)}</td><td class="num">${k.calls}</td><td class="num">${mins(k.seconds || 0)}</td>
      <td class="num">${(k.tokensIn || 0).toLocaleString()}</td><td class="num">${(k.tokensOut || 0).toLocaleString()}</td>
      <td class="num">${usd(k.charged, 3)}</td><td class="num">${usd(k.cost, 3)}</td><td class="num ${profitClass(k.charged - k.cost)}">${usd(k.charged - k.cost, 3)}</td></tr>`).join('')
      : '<tr><td colspan="8" class="muted">本月还没有用量</td></tr>')
}

function drawChart(days) {
  const map = new Map(days.map((d) => [d.day, d]))
  const list = []
  for (let i = 29; i >= 0; i--) {
    const d = new Date(Date.now() - i * 86400000)
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    list.push({ day: key, ...(map.get(key) || { charged: 0, cost: 0 }) })
  }
  const W = 900, H = 220, pad = 28, top = 10
  const max = Math.max(1, ...list.map((d) => Math.max(d.charged, d.cost)))
  const bw = (W - pad) / list.length
  const y = (v) => H - pad - (v / max) * (H - pad - top)
  let svg = `<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" role="img" aria-label="近 30 天计费与成本">`
  for (let g = 0; g <= 4; g++) {
    const v = (max * g) / 4
    svg += `<line class="grid-line" x1="${pad}" x2="${W}" y1="${y(v)}" y2="${y(v)}"/><text x="0" y="${y(v) + 4}">${(v / 1e6).toFixed(v / 1e6 < 1 ? 2 : 0)}</text>`
  }
  list.forEach((d, i) => {
    const x = pad + i * bw
    const w = Math.max(2, bw / 2 - 2)
    svg += `<rect x="${x + 1}" y="${y(d.charged)}" width="${w}" height="${H - pad - y(d.charged)}" rx="2" fill="var(--accent)"><title>${d.day} 计费 ${usd(d.charged, 3)}</title></rect>`
    svg += `<rect x="${x + 1 + w + 1}" y="${y(d.cost)}" width="${w}" height="${H - pad - y(d.cost)}" rx="2" fill="var(--cost)"><title>${d.day} 成本 ${usd(d.cost, 3)}</title></rect>`
    if (i % 5 === 0) svg += `<text x="${x}" y="${H - 8}">${d.day.slice(5)}</text>`
  })
  $('#chart').innerHTML = svg + '</svg>'
}

// ---------- 授权码列表 ----------

let licenses = []
let searchHash = null

async function loadLicenses() {
  licenses = (await api('licenses')).licenses
  renderLicenses()
}

const codeLabel = (l) => (l.kind === 'subscription' ? '订阅凭证' : l.hint ? `DW-…-${l.hint}` : `#${l.hash.slice(0, 8)}`)

function renderLicenses() {
  const q = $('#search').value.trim().toLowerCase()
  const f = $('#filter').value
  const rows = licenses.filter((l) => {
    if (f === 'active' && (l.disabled || !l.last_used_at)) return false
    if (f === 'code' && l.kind !== 'code') return false
    if (f === 'subscription' && l.kind !== 'subscription') return false
    if (f === 'disabled' && !l.disabled) return false
    if (!q) return true
    if (searchHash && l.hash === searchHash) return true
    return [l.label, l.note, l.hint, l.hash.slice(0, 12)].some((v) => String(v || '').toLowerCase().includes(q))
  })
  $('#license-table').innerHTML = `<tr><th>授权码</th><th>标签</th><th class="num">额度</th><th class="num">余额</th><th class="num">单价/分钟</th>
    <th class="num">设备</th><th class="num">人声</th><th class="num">用户计费</th><th class="num">API 成本</th><th class="num">毛利</th><th>最近使用</th><th>状态</th></tr>` +
    (rows.length ? rows.map((l) => `<tr class="clickable" data-hash="${l.hash}">
      <td class="mono">${esc(codeLabel(l))}</td><td>${esc(l.label)}${l.note ? ` <span class="muted">· ${esc(l.note.slice(0, 24))}</span>` : ''}</td>
      <td class="num">${usd(l.granted_micros)}</td><td class="num">${usd(l.balance_micros)}</td>
      <td class="num">${l.price_micros_per_minute ? usd(l.price_micros_per_minute, 4) : '<span class="muted">标准</span>'}</td>
      <td class="num">${l.activeDevices}<span class="muted"> / ${l.devices}</span></td><td class="num">${mins(l.seconds)}</td>
      <td class="num">${usd(l.charged, 3)}</td><td class="num">${usd(l.cost, 3)}</td><td class="num ${profitClass(l.charged - l.cost)}">${usd(l.charged - l.cost, 3)}</td>
      <td>${ago(l.last_used_at)}</td><td>${l.disabled ? '<span class="pill off">已废弃</span>' : l.last_used_at ? '<span class="pill on">使用中</span>' : '<span class="pill">未使用</span>'}</td></tr>`).join('')
      : '<tr><td colspan="12" class="muted">没有匹配的授权码</td></tr>')
  $$('#license-table tr.clickable').forEach((tr) => tr.addEventListener('click', () => openDetail(tr.dataset.hash)))
}

const normalize = (c) => c.toUpperCase().replace(/[^0-9A-Z]/g, '')
async function sha256(text) {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

$('#search').addEventListener('input', async () => {
  const n = normalize($('#search').value)
  searchHash = n.length >= 16 ? await sha256(n) : null
  renderLicenses()
})
$('#filter').addEventListener('change', renderLicenses)
$('#reload').addEventListener('click', loadLicenses)

// ---------- 详情与修改 ----------

const dialog = $('#detail')
$('#detail-close').addEventListener('click', () => dialog.close())

async function openDetail(hash) {
  const d = await api(`license?hash=${hash}`)
  renderDetail(d)
  if (!dialog.open) dialog.showModal()
}

function renderDetail(d) {
  const l = d.license
  const t = d.totals
  $('#detail-title').textContent = `${codeLabel(l)} ${l.label ? `· ${l.label}` : ''}`
  $('#detail-body').innerHTML = `
    <div class="kv">
      <div><span>状态</span><b>${l.disabled ? `已废弃（${when(l.disabled_at)}）` : '有效'}</b></div>
      <div><span>类型</span><b>${l.kind === 'subscription' ? `订阅 ${esc(d.subscription?.product_id || '')}` : '授权码'}</b></div>
      <div><span>额度 / 余额</span><b>${usd(l.granted_micros)} / ${usd(l.balance_micros)}</b></div>
      <div><span>单价</span><b>${l.price_micros_per_minute ? `${usd(l.price_micros_per_minute, 4)} / 分钟` : '标准单价'}</b></div>
      <div><span>售价</span><b>${usd(l.sale_micros ?? (l.kind === 'code' ? l.granted_micros : null))}</b></div>
      <div><span>累计人声</span><b>${mins(t.seconds)}</b></div>
      <div><span>用户计费</span><b>${usd(t.charged, 3)}</b></div>
      <div><span>API 成本</span><b>${usd(t.cost, 3)}</b></div>
      <div><span>毛利</span><b class="${profitClass(t.charged - t.cost)}">${usd(t.charged - t.cost, 3)}</b></div>
      <div><span>创建 / 首次使用</span><b>${when(l.created_at)} / ${when(l.first_used_at)}</b></div>
      <div><span>到期</span><b>${when(l.expires_at)}</b></div>
      <div><span>哈希</span><b class="mono">${l.hash.slice(0, 16)}…</b></div>
    </div>
    <div class="edit">
      <label>标签<input id="e-label" value="${esc(l.label)}"></label>
      <label>备注<input id="e-note" value="${esc(l.note)}"></label>
      <label>单价（美元/分钟，空 = 标准）<input id="e-price" type="number" step="0.0001" value="${l.price_micros_per_minute ? l.price_micros_per_minute / 1e6 : ''}"></label>
      <label>售价（美元）<input id="e-sale" type="number" step="0.01" value="${l.sale_micros != null ? l.sale_micros / 1e6 : ''}"></label>
      <label>追加额度（美元，可为负）<input id="e-add" type="number" step="0.01" placeholder="0"></label>
      <div class="row"><button id="e-save" class="primary">保存</button>
        ${l.disabled ? '<button id="e-restore" class="ghost">恢复</button>' : '<button id="e-disable" class="danger">废弃</button>'}</div>
    </div>
    <p id="e-error" class="error"></p>
    <h2 class="section-title">设备（${d.devices.length}）</h2>
    <table class="table"><tr><th>设备</th><th>平台</th><th>首次</th><th>最近</th><th class="num">请求</th></tr>
      ${d.devices.map((v) => `<tr><td class="mono">${esc(v.device)}${v.estimated ? ' <span class="muted">（旧版，按来源估算）</span>' : ''}</td><td>${esc(v.platform || '—')}</td>
        <td>${when(v.first_seen)}</td><td>${ago(v.last_seen)}</td><td class="num">${v.requests}</td></tr>`).join('') || '<tr><td colspan="5" class="muted">还没有设备</td></tr>'}
    </table>
    <h2 class="section-title">最近用量</h2>
    <table class="table"><tr><th>时间</th><th>用途</th><th class="num">人声</th><th class="num">token</th><th class="num">计费</th><th class="num">成本</th></tr>
      ${d.usage.map((u) => `<tr><td>${when(u.at)}</td><td>${esc(KIND[u.kind] || u.kind)}</td><td class="num">${u.seconds ? `${u.seconds} 秒` : ''}</td>
        <td class="num">${u.tokens_in || u.tokens_out ? `${u.tokens_in} / ${u.tokens_out}` : ''}</td><td class="num">${usd(u.micros, 4)}</td><td class="num">${usd(u.cost_micros, 4)}</td></tr>`).join('') || '<tr><td colspan="6" class="muted">还没有用量</td></tr>'}
    </table>`
  const save = async (patch) => {
    try {
      renderDetail(await api('license', { hash: l.hash, ...patch }))
      loadLicenses()
    } catch (e) { $('#e-error').textContent = e.message }
  }
  $('#e-save').addEventListener('click', () => {
    const price = $('#e-price').value.trim()
    const sale = $('#e-sale').value.trim()
    const add = Number($('#e-add').value || 0)
    save({
      label: $('#e-label').value, note: $('#e-note').value,
      priceMicrosPerMinute: price ? Math.round(Number(price) * 1e6) : null,
      saleMicros: sale ? Math.round(Number(sale) * 1e6) : null,
      addMicros: Math.round(add * 1e6),
    })
  })
  $('#e-disable')?.addEventListener('click', () => {
    // 不用 confirm()：点两次才生效
    const b = $('#e-disable')
    if (b.dataset.armed) return save({ disabled: true })
    b.dataset.armed = '1'
    b.textContent = '再点一次确认废弃'
    setTimeout(() => { delete b.dataset.armed; b.textContent = '废弃' }, 4000)
  })
  $('#e-restore')?.addEventListener('click', () => save({ disabled: false }))
}

// ---------- 生成授权码 ----------

const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ' // Crockford Base32，与 App 和服务端一致
function newCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(20))
  const body = [...bytes].map((b) => ALPHABET[b & 31]).join('')
  return `DW-${body.match(/.{5}/g).join('-')}`
}

const form = $('#create-form')
const field = (n) => form.elements[n]
function grant() {
  const price = Number(field('price').value) || null
  if (field('mode').value === 'minutes') {
    if (!price) return null
    return Math.round(Number(field('minutes').value) * price * 1e6)
  }
  return Math.round(Number(field('usd').value) * 1e6)
}
function preview() {
  const minutesMode = field('mode').value === 'minutes'
  $('#grant-usd').hidden = minutesMode
  $('#grant-minutes').hidden = !minutesMode
  const g = grant()
  const price = Number(field('price').value) || null
  const n = Number(field('count').value) || 0
  $('#create-preview').textContent = minutesMode && !price ? '按分钟发码需要填写单价'
    : g ? `每个码额度 ${usd(g)}${price ? `，约 ${Math.floor(g / 1e6 / price)} 分钟人声` : ''}；共 ${n} 个` : ''
}
form.addEventListener('input', preview)
preview()

let lastCodes = []
form.addEventListener('submit', async (e) => {
  e.preventDefault()
  $('#create-error').textContent = ''
  const g = grant()
  if (!g) return ($('#create-error').textContent = '请填写额度（按分钟时需要单价）')
  const count = Math.min(200, Math.max(1, Number(field('count').value) || 1))
  const price = Number(field('price').value) || null
  const sale = field('sale').value.trim()
  const codes = Array.from({ length: count }, newCode)
  const payload = await Promise.all(codes.map(async (c) => ({ hash: await sha256(normalize(c)), hint: c.slice(-5) })))
  try {
    const r = await api('licenses', {
      codes: payload, grantMicros: g, priceMicrosPerMinute: price ? Math.round(price * 1e6) : null,
      saleMicros: sale ? Math.round(Number(sale) * 1e6) : g, label: field('label').value.trim(), note: field('note').value.trim(),
    })
    lastCodes = codes.map((c) => ({ code: c, usd: g / 1e6, price, label: field('label').value.trim() }))
    $('#created-codes').textContent = codes.join('\n')
    $('#created').hidden = false
    $('#create-error').textContent = r.added === count ? '' : `只导入了 ${r.added} 个`
  } catch (ex) { $('#create-error').textContent = ex.message }
})
$('#copy-codes').addEventListener('click', async () => {
  await navigator.clipboard.writeText(lastCodes.map((c) => c.code).join('\n'))
  $('#copy-codes').textContent = '已复制'
  setTimeout(() => ($('#copy-codes').textContent = '复制全部'), 1500)
})
$('#download-codes').addEventListener('click', () => {
  const csv = 'code,usd,price_per_minute,label,created\n' + lastCodes.map((c) => `${c.code},${c.usd},${c.price ?? ''},${c.label},${new Date().toISOString()}`).join('\n')
  const a = document.createElement('a')
  a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }))
  a.download = `dayweave-codes-${Date.now()}.csv`
  a.click()
})
window.addEventListener('beforeunload', (e) => { if (!$('#created').hidden) e.preventDefault() })

// ---------- 启动 ----------

fetch('/admin/api/session', { credentials: 'same-origin' }).then((r) => r.json()).then((s) => (s.authed ? showApp() : showLogin()), showLogin)
