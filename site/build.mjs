// 拾日落地页：零依赖静态生成。node site/build.mjs → site/dist/
// 视觉沿用 App：Liquid Glass + 小清新 + 朝花夕拾（浅色为朝、深色为夕），动效用数学函数驱动。
import { mkdirSync, writeFileSync, readFileSync, cpSync, rmSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { T, LEGAL, SITE, REPO, UPDATED, PLANS, CODE_USD, PER_MINUTE_USD, saving } from './content.mjs'

const ROOT = dirname(fileURLToPath(import.meta.url))
const DIST = join(ROOT, 'dist')
const ASSET_V = Date.now().toString(36)

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
const md = (src) =>
  src.trim().split(/\n{2,}/).map((block) => {
    const inline = (s) => esc(s)
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/\[(.+?)\]\((.+?)\)/g, '<a href="$2">$1</a>')
    if (block.startsWith('## ')) return `<h2>${inline(block.slice(3))}</h2>`
    if (block.startsWith('- ')) return `<ul>${block.split('\n').map((l) => `<li>${inline(l.replace(/^- /, ''))}</li>`).join('')}</ul>`
    return `<p>${inline(block)}</p>`
  }).join('\n')

const ICONS = {
  bell: '<path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z"/><path d="M10 20.5a2 2 0 0 0 4 0"/>',
  voice: '<path d="M4 12h1.5M8 8v8M12 5v14M16 9v6M19.5 12H20"/>',
  leaf: '<path d="M5 19c0-8 5-13 14-14-1 9-6 14-14 14z"/><path d="M5 19 13 11"/>',
  globe: '<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c2.5 2.6 3.6 5.4 3.6 8.5s-1.1 5.9-3.6 8.5c-2.5-2.6-3.6-5.4-3.6-8.5s1.1-5.9 3.6-8.5z"/>',
  text: '<path d="M6 4h9l3 3v13H6z"/><path d="M9 11h6M9 14.5h6M9 18h3.5"/>',
  key: '<circle cx="8.5" cy="14.5" r="4"/><path d="m11.5 11.5 8-8M16.5 6.5l2.5 2.5M14 9l2 2"/>',
  check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
  arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
  android: '<path d="M7 10h10v7.5a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 7 17.5z"/><path d="M7 9a5 5 0 0 1 10 0z"/><path d="m8.5 4.5 1.2 1.8M15.5 4.5l-1.2 1.8"/>',
  code: '<path d="m8.5 7.5-5 4.5 5 4.5M15.5 7.5l5 4.5-5 4.5"/>',
  alarm: '<circle cx="12" cy="13" r="7"/><path d="M12 9.5V13l2.5 1.5M4.5 5.5l2.5-2M19.5 5.5l-2.5-2"/>',
  cal: '<rect x="4" y="5.5" width="16" height="14" rx="3"/><path d="M4 10h16M8.5 3.5v4M15.5 3.5v4"/>',
}
const icon = (name, cls = 'ic') => `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true">${ICONS[name]}</svg>`

// 抛物线落瓣的轨迹：从晨光处的花，沿贝塞尔曲线落到暮色里的掌心。
const MARK = readFileSync(join(ROOT, '../android/tools/icon-preview.svg'), 'utf8')
  .replace(/width="432" height="432"/, 'width="40" height="40" aria-hidden="true"')

const alt = (path) => {
  const en = `${SITE}${path || '/'}`
  const zh = `${SITE}/zh${path || '/'}`
  return `<link rel="alternate" hreflang="en" href="${en}">
<link rel="alternate" hreflang="zh-Hans" href="${zh}">
<link rel="alternate" hreflang="x-default" href="${en}">`
}

function head(t, { title, description, path, jsonld = [] }) {
  const url = `${SITE}${t.prefix}${path}`
  return `<!doctype html>
<html lang="${t.htmlLang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>${esc(title)}</title>
<meta name="description" content="${esc(description)}">
<meta name="keywords" content="${esc(t.keywords)}">
<link rel="canonical" href="${url}">
${alt(path)}
<meta name="theme-color" content="#FBF8FC" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#0E0C14" media="(prefers-color-scheme: dark)">
<meta name="color-scheme" content="light dark">
<meta name="robots" content="index, follow, max-image-preview:large">
<meta property="og:type" content="website">
<meta property="og:site_name" content="Dayweave · 拾日">
<meta property="og:title" content="${esc(title)}">
<meta property="og:description" content="${esc(description)}">
<meta property="og:url" content="${url}">
<meta property="og:image" content="${SITE}/og.png">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta property="og:locale" content="${t.lang === 'zh' ? 'zh_CN' : 'en_US'}">
<meta property="og:locale:alternate" content="${t.lang === 'zh' ? 'en_US' : 'zh_CN'}">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="${esc(title)}">
<meta name="twitter:description" content="${esc(description)}">
<meta name="twitter:image" content="${SITE}/og.png">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="icon" href="/favicon-32.png" sizes="32x32" type="image/png">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<link rel="manifest" href="/site.webmanifest">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Instrument+Serif:ital@0;1&display=swap">
<link rel="stylesheet" href="/assets/site.css?v=${ASSET_V}">
${jsonld.map((j) => `<script type="application/ld+json">${JSON.stringify(j)}</script>`).join('\n')}
</head>`
}

const nav = (t, home) => {
  const h = (id) => (home ? `#${id}` : `${t.prefix}/#${id}`)
  return `<header class="nav glass" data-nav>
  <a class="brand" href="${t.prefix}/" aria-label="Dayweave">${MARK}<span>Dayweave<em>拾日</em></span></a>
  <nav aria-label="Primary">
    <a href="${h('how')}">${t.nav.how}</a><a href="${h('features')}">${t.nav.features}</a><a href="${h('privacy')}">${t.nav.privacy}</a><a href="${h('pricing')}">${t.nav.pricing}</a><a href="${h('faq')}">${t.nav.faq}</a>
  </nav>
  <a class="lang" href="${t.switchLang.href}" hreflang="${t.lang === 'zh' ? 'en' : 'zh-Hans'}">${t.switchLang.label}</a>
</header>`
}

const footer = (t) => `<footer class="foot">
  <div class="foot-brand">${MARK}<div><strong>Dayweave · 拾日</strong><p>${t.footer.made}</p></div></div>
  <nav aria-label="Footer">
    <a href="${t.prefix}/privacy/">${t.footer.privacy}</a><a href="${t.prefix}/terms/">${t.footer.terms}</a><a href="${t.prefix}/support/">${t.footer.support}</a><a href="${REPO}" rel="noopener">${t.footer.source}</a><a href="${t.switchLang.href}">${t.switchLang.label}</a>
  </nav>
  <p class="copy">© 2026 Dante · Dayweave</p>
</footer>
<script src="/assets/site.js?v=${ASSET_V}" defer></script>
</body>
</html>`

// 手机里的界面：一天的日轨（太阳沿圆弧走过，花瓣沿贝塞尔落下）+ 主动提醒卡 + 昨日小结。
const phone = (p) => `<div class="phone" aria-hidden="true">
  <div class="screen">
    <div class="island"></div>
    <div class="ph-top">${MARK}<span class="ph-word">Dayweave</span></div>
    <div class="ph-greet">${p.greeting}</div>
    <div class="ph-date">${p.date}</div>
    <svg class="dayarc" viewBox="0 0 300 92">
      <path class="arc-trail" d="M18 78 Q150 -18 282 78"/>
      <path class="arc-lit" d="M18 78 Q150 -18 282 78" pathLength="1"/>
      <circle class="sun" r="5"/>
      <g class="rays"><path d="M24 80h-12M18 74v-8M12 76l-5-4M24 76l5-4"/></g>
      <path class="moon" d="M286 60a8 8 0 1 0 6 13 6.5 6.5 0 0 1-6-13z"/>
      <path class="fall-petal" d="M0 0c-3-1-3.6-5-1.8-6l1.6.7 1.4-1c2 .7 2.2 4.7-1.2 6.3z"/>
    </svg>
    <div class="ph-watch"><span class="dot"></span>${p.watching}</div>
    <div class="ph-card">
      <div class="ph-card-h"><span class="ph-cal">${icon('cal')}</span><div><small>${p.cardTime}</small><b>${p.cardTitle}</b></div></div>
      <p>${p.cardBody}</p>
      <i>${p.cardQuote}</i>
      <div class="ph-actions"><span class="chip dark">${icon('alarm')}${p.remind}</span><span class="chip light">${icon('cal')}${p.add}</span></div>
    </div>
    <div class="ph-day">
      <div class="ph-day-h"><div><b>${p.dayLabel}</b><small>${p.dayMeta}</small></div><div class="faces"><span>M</span><span>J</span><span>Me</span></div></div>
      <h4>${p.dayTitle}</h4>
      <p>${p.dayBody}</p>
    </div>
    <div class="ph-tabs glass">${p.tabs.map((x, i) => `<span class="${i === 0 ? 'on' : ''}">${x}</span>`).join('')}</div>
  </div>
</div>`

function home(t) {
  const P = t.pricing
  const codeMinutes = Math.round(CODE_USD / PER_MINUTE_USD)
  const jsonld = [
    {
      '@context': 'https://schema.org', '@type': 'SoftwareApplication',
      name: 'Dayweave', alternateName: '拾日', url: `${SITE}${t.prefix}/`, image: `${SITE}/icon-512.png`,
      applicationCategory: 'ProductivityApplication', operatingSystem: 'Android, iOS', inLanguage: ['en', 'zh-Hans'],
      description: t.description,
      offers: [
        { '@type': 'Offer', name: P.source.name, price: '0', priceCurrency: 'USD' },
        ...PLANS.flatMap((p) => [
          { '@type': 'Offer', name: `Dayweave Cloud ${P.names[p.id]} (monthly)`, price: String(p.usd), priceCurrency: 'USD', category: 'subscription' },
          ...(p.yearly ? [{ '@type': 'Offer', name: `Dayweave Cloud ${P.names[p.id]} (yearly)`, price: String(p.yearly), priceCurrency: 'USD', category: 'subscription' }] : []),
        ]),
      ],
      author: { '@type': 'Person', name: 'Dante' },
    },
    { '@context': 'https://schema.org', '@type': 'Organization', name: 'Dayweave', url: SITE, logo: `${SITE}/icon-512.png`, sameAs: [REPO] },
    {
      '@context': 'https://schema.org', '@type': 'FAQPage',
      mainEntity: t.faq.items.map(([q, a]) => ({ '@type': 'Question', name: q, acceptedAnswer: { '@type': 'Answer', text: a } })),
    },
  ]
  return `${head(t, { title: t.title, description: t.description, path: '/', jsonld })}
<body class="home">
<div class="aurora" aria-hidden="true"><i></i><i></i><i></i><i></i></div>
<canvas class="petals" aria-hidden="true"></canvas>
${nav(t, true)}
<main>
<section class="hero">
  <div class="hero-copy">
    <p class="eyebrow">${t.hero.eyebrow}</p>
    <h1>${t.hero.title.map((l) => `<span>${l}</span>`).join('')}</h1>
    <p class="lead">${t.hero.lead}</p>
    <div class="ctas">
      <a class="btn primary" href="/download/dayweave.apk" download>${icon('android')}${t.hero.primary}</a>
      <a class="btn ghost" href="${REPO}" rel="noopener">${icon('code')}${t.hero.secondary}</a>
    </div>
    <p class="note">${t.hero.note}</p>
  </div>
  <div class="hero-art">${phone(t.phone)}</div>
</section>

<section id="how" class="section">
  <h2 class="title reveal">${t.how.title}</h2>
  <p class="sub reveal">${t.how.lead}</p>
  <ol class="steps">
    ${t.how.steps.map((s) => `<li class="glass card reveal"><span class="k">${s.k}</span><h3>${s.t}</h3><p>${s.d}</p></li>`).join('\n    ')}
  </ol>
</section>

<section id="features" class="section">
  <h2 class="title reveal">${t.features.title}</h2>
  <div class="grid">
    ${t.features.items.map((f) => `<article class="glass card feature reveal">${icon(f.icon, 'ic big')}<h3>${f.t}</h3><p>${f.d}</p></article>`).join('\n    ')}
  </div>
</section>

<section id="privacy" class="section privacy">
  <div class="glass panel reveal">
    <h2 class="title">${t.privacy.title}</h2>
    <ul class="checks">${t.privacy.points.map((x) => `<li>${icon('check')}<span>${x}</span></li>`).join('')}</ul>
    <a class="link" href="${t.prefix}/privacy/">${t.privacy.link}${icon('arrow')}</a>
  </div>
</section>

<section id="pricing" class="section">
  <h2 class="title reveal">${P.title}</h2>
  <p class="sub reveal">${P.lead}</p>
  <div class="plans">
    ${PLANS.map((p) => `<article class="glass card plan reveal${p.featured ? ' featured' : ''}">
      ${p.featured ? `<span class="badge">${P.featured}</span>` : ''}
      <h3>${P.names[p.id]}</h3>
      <p class="blurb">${P.blurbs[p.id]}</p>
      <p class="price"><b>$${p.usd.toLocaleString('en-US')}</b><span>${P.monthly}</span></p>
      <p class="save">${saving(p) > 0 ? P.save(saving(p)) : '&nbsp;'}</p>
      <ul class="checks"><li>${icon('check')}<span>${P.hours(p.hours)}</span></li><li>${icon('check')}<span>${P.perDay(p.hours)}</span></li></ul>
      <p class="yearly">${p.yearly ? P.yearly(p.yearly.toLocaleString('en-US'), saving(p, true)) : P.monthlyOnly}</p>
      <p class="cta-note">${P.subscribe}</p>
    </article>`).join('\n    ')}
  </div>
  <div class="extras">
    <article class="glass card extra reveal"><div><h3>${P.source.name}</h3><p>${P.source.d}</p></div><div class="extra-side"><b>${P.source.price}</b><a class="link" href="${REPO}" rel="noopener">${P.source.cta}${icon('arrow')}</a></div></article>
    <article class="glass card extra reveal"><div><h3>${P.code.name}</h3><p>${P.code.d(CODE_USD, codeMinutes)}</p></div><div class="extra-side"><b>$${CODE_USD}</b><span class="muted">${P.code.cta}</span></div></article>
  </div>
  <p class="fine reveal">${P.fine}</p>
</section>

<section id="faq" class="section narrow">
  <h2 class="title reveal">${t.faq.title}</h2>
  ${t.faq.items.map(([q, a]) => `<details class="glass qa reveal"><summary>${q}</summary><p>${a}</p></details>`).join('\n  ')}
</section>
</main>
${footer(t)}`
}

function legal(t, kind) {
  const pg = t.pages[kind]
  const crumbs = {
    '@context': 'https://schema.org', '@type': 'BreadcrumbList',
    itemListElement: [
      { '@type': 'ListItem', position: 1, name: 'Dayweave', item: `${SITE}${t.prefix}/` },
      { '@type': 'ListItem', position: 2, name: pg.title, item: `${SITE}${t.prefix}/${kind}/` },
    ],
  }
  return `${head(t, { title: `${pg.title} · Dayweave`, description: pg.description, path: `/${kind}/`, jsonld: [crumbs] })}
<body class="doc">
<div class="aurora" aria-hidden="true"><i></i><i></i><i></i><i></i></div>
${nav(t, false)}
<main class="section narrow">
  <article class="glass panel prose">
    <a class="back" href="${t.prefix}/">← ${t.back}</a>
    <h1>${pg.title}</h1>
    <p class="muted">${t.updated} ${UPDATED}</p>
    ${md(LEGAL[t.lang][kind])}
  </article>
</main>
${footer(t)}`
}

function write(rel, content) {
  const f = join(DIST, rel)
  mkdirSync(dirname(f), { recursive: true })
  writeFileSync(f, content)
}

rmSync(DIST, { recursive: true, force: true })
const pages = []
for (const t of Object.values(T)) {
  write(`${t.prefix}/index.html`.replace(/^\//, ''), home(t))
  pages.push(`${t.prefix}/`)
  for (const kind of ['privacy', 'terms', 'support']) {
    write(`${t.prefix}/${kind}/index.html`.replace(/^\//, ''), legal(t, kind))
    pages.push(`${t.prefix}/${kind}/`)
  }
}

cpSync(join(ROOT, 'assets'), join(DIST, 'assets'), { recursive: true })
cpSync(join(ROOT, 'public'), DIST, { recursive: true })

const enPaths = pages.filter((p) => !p.startsWith('/zh'))
write('sitemap.xml', `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">
${enPaths.flatMap((p) => [p, `/zh${p}`].map((loc) => `  <url>
    <loc>${SITE}${loc}</loc>
    <lastmod>${UPDATED}</lastmod>
    <xhtml:link rel="alternate" hreflang="en" href="${SITE}${p}"/>
    <xhtml:link rel="alternate" hreflang="zh-Hans" href="${SITE}/zh${p}"/>
    <xhtml:link rel="alternate" hreflang="x-default" href="${SITE}${p}"/>
  </url>`)).join('\n')}
</urlset>
`)
write('robots.txt', `User-agent: *
Allow: /
Disallow: /download/

Sitemap: ${SITE}/sitemap.xml
`)
const en = T.en
write('llms.txt', `# Dayweave (拾日)

> ${en.description}

Dayweave is an ambient personal secretary for Android (iPhone coming soon). It listens in the background, keeps only human speech using on-device voice activity detection, transcribes it in the cloud (Google Speech-to-Text, Chinese and English including mixed speech), stores the transcript as text on the phone, deletes the audio, and uses an AI agent to write daily digests, follow-ups, insights and proactive reminders (alarms and calendar events).

## Pricing
- Source edition: free, bring your own Google Cloud and AI keys.
${PLANS.map((p) => `- Dayweave Cloud ${en.pricing.names[p.id]}: $${p.usd.toLocaleString('en-US')}/month${p.yearly ? ` or $${p.yearly.toLocaleString('en-US')}/year` : ''}, ${p.hours} hours of transcribed voice per month.`).join('\n')}
- Pay as you go: $${CODE_USD} license code, $${PER_MINUTE_USD} per minute of voice; no account needed (Android).
- Only speech is billed; silence, music, podcasts and video are filtered on the device.

## Privacy
- Audio is deleted after transcription; transcripts live on the user's device.
- Dayweave Cloud stores no audio or text, only usage minutes and hashed license or subscription identifiers.
- No accounts, ads or third-party tracking.

## Links
- [Home](${SITE}/)
- [中文主页](${SITE}/zh/)
- [Privacy policy](${SITE}/privacy/)
- [Terms](${SITE}/terms/)
- [Support](${SITE}/support/)
- [Source code](${REPO})
`)
write('site.webmanifest', JSON.stringify({
  name: 'Dayweave · 拾日', short_name: 'Dayweave', start_url: '/', display: 'standalone',
  background_color: '#FBF8FC', theme_color: '#F98BA8',
  icons: [{ src: '/icon-192.png', sizes: '192x192', type: 'image/png' }, { src: '/icon-512.png', sizes: '512x512', type: 'image/png' }],
}, null, 2))
write('404.html', legal(T.en, 'support')
  .replace('<meta name="robots" content="index, follow, max-image-preview:large">', '<meta name="robots" content="noindex">')
  .replace(/<h1>[\s\S]*<\/article>/, '<h1>Lost a petal</h1><p>This page drifted away. 这一页随风飘走了。</p></article>'))
console.log(`built ${pages.length} pages → ${DIST}`)
