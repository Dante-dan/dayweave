// 拾日落地页动效：全部由数学函数驱动，遵守“减少动态效果”。
(() => {
  const doc = document.documentElement
  doc.classList.add('js')
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches
  const clamp = (x, a, b) => Math.min(b, Math.max(a, x))

  // 阻尼弹簧的解析解：x(t) = 1 − e^(−ζωt)·(cos ω_d t + ζω/ω_d · sin ω_d t)，欠阻尼，轻微过冲后落定。
  const spring = (t, w = 11, z = 0.72) => {
    const wd = w * Math.sqrt(1 - z * z)
    return 1 - Math.exp(-z * w * t) * (Math.cos(wd * t) + ((z * w) / wd) * Math.sin(wd * t))
  }

  // 进场：元素进入视口后按弹簧曲线上浮、淡入；同一批错开 70ms。
  const reveals = [...document.querySelectorAll('.reveal')]
  if (reduce || !('IntersectionObserver' in window)) {
    reveals.forEach((el) => { el.style.opacity = 1; el.style.transform = 'none' })
  } else {
    let batch = 0
    const io = new IntersectionObserver((entries) => {
      entries.filter((e) => e.isIntersecting).forEach((e) => {
        io.unobserve(e.target)
        const delay = (batch++ % 6) * 70
        setTimeout(() => {
          const t0 = performance.now()
          const step = (now) => {
            const t = (now - t0) / 1000
            const s = spring(t)
            e.target.style.opacity = clamp(t * 3.2, 0, 1)
            e.target.style.transform = `translateY(${(1 - s) * 24}px)`
            if (t < 1.4) requestAnimationFrame(step)
            else e.target.style.transform = 'none'
          }
          requestAnimationFrame(step)
        }, delay)
      })
      setTimeout(() => { batch = 0 }, 400)
    }, { rootMargin: '0px 0px -8% 0px' })
    reveals.forEach((el) => io.observe(el))
  }

  // 柔光：四团光斑沿李萨如曲线 (A sin(at+δ), B sin(bt)) 缓慢漂移。
  const blobs = [...document.querySelectorAll('.aurora i')]
  const lissa = [[60, 40, 0.11, 0.07, 0], [50, 70, 0.08, 0.13, 1.3], [80, 50, 0.06, 0.09, 2.1], [40, 60, 0.09, 0.05, 3.7]]

  // 落瓣：带空气阻力的抛体 v' = g − k·v + 风，风是两个正弦叠加；花瓣绕自身转动，翻面时宽度按 cos 收缩。
  const canvas = document.querySelector('.petals')
  const ctx = canvas && canvas.getContext('2d')
  const dpr = Math.min(2, window.devicePixelRatio || 1)
  let W = 0, H = 0
  const resize = () => {
    if (!canvas) return
    W = canvas.clientWidth; H = canvas.clientHeight
    canvas.width = W * dpr; canvas.height = H * dpr
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  }
  resize(); addEventListener('resize', resize)
  const dark = matchMedia('(prefers-color-scheme: dark)')
  const N = innerWidth < 600 ? 5 : 9
  const spawn = (p = {}) => Object.assign(p, {
    x: Math.random() * W * 0.9, y: -20 - Math.random() * H * 0.6,
    vx: 10 + Math.random() * 20, vy: 0, size: 6 + Math.random() * 6,
    rot: Math.random() * Math.PI * 2, spin: (Math.random() - 0.5) * 2.2,
    flip: Math.random() * Math.PI * 2, flipRate: 1 + Math.random() * 1.6, k: 0.9 + Math.random() * 0.5,
  })
  const petals = Array.from({ length: N }, () => spawn())
  const drawPetal = (p) => {
    ctx.save()
    ctx.translate(p.x, p.y); ctx.rotate(p.rot); ctx.scale(Math.cos(p.flip) * 0.8 + 0.2, 1)
    const s = p.size
    ctx.beginPath()
    ctx.moveTo(0, s)
    ctx.bezierCurveTo(-s * 0.9, s * 0.4, -s * 0.8, -s * 0.8, -s * 0.22, -s)
    ctx.lineTo(0, -s * 0.78); ctx.lineTo(s * 0.22, -s)
    ctx.bezierCurveTo(s * 0.8, -s * 0.8, s * 0.9, s * 0.4, 0, s)
    ctx.fillStyle = dark.matches ? 'rgba(255,160,200,.30)' : 'rgba(247,150,185,.42)'
    ctx.fill()
    ctx.restore()
  }

  // 日轨：太阳沿二次贝塞尔从日出走到访客当地此刻的位置（6:00→18:00），点亮走过的轨迹。
  const arc = document.querySelector('.dayarc')
  const sun = arc && arc.querySelector('.sun')
  const lit = arc && arc.querySelector('.arc-lit')
  const fall = arc && arc.querySelector('.fall-petal')
  const P0 = [18, 78], P1 = [150, -18], P2 = [282, 78]
  const bez = (u) => [0, 1].map((i) => (1 - u) ** 2 * P0[i] + 2 * (1 - u) * u * P1[i] + u * u * P2[i])
  const now = new Date()
  const target = clamp((now.getHours() + now.getMinutes() / 60 - 6) / 12, 0.06, 0.96)
  const arcStart = performance.now()

  // 手机随指针轻微倾斜，同样用弹簧追随。
  const phone = document.querySelector('.phone')
  let tx = 0, ty = 0, cx = 0, cy = 0, vx = 0, vy = 0
  if (phone && matchMedia('(pointer: fine)').matches) {
    addEventListener('pointermove', (e) => {
      tx = (e.clientX / innerWidth - 0.5) * 10
      ty = (e.clientY / innerHeight - 0.5) * -8
    })
  }

  if (reduce) {
    if (arc) {
      const [x, y] = bez(target)
      sun.setAttribute('cx', x); sun.setAttribute('cy', y)
      lit.style.strokeDashoffset = 1 - target
      fall.style.opacity = 0
    }
    return
  }

  let last = performance.now()
  const frame = (t) => {
    const dt = Math.min(0.05, (t - last) / 1000); last = t
    const s = t / 1000

    blobs.forEach((b, i) => {
      const [A, B, a, bb, d] = lissa[i]
      b.style.transform = `translate3d(${A * Math.sin(a * s + d)}px, ${B * Math.sin(bb * s)}px, 0)`
    })

    if (ctx && W) {
      ctx.clearRect(0, 0, W, H)
      const wind = 18 * Math.sin(s * 0.35) + 8 * Math.sin(s * 1.1 + 1)
      petals.forEach((p) => {
        p.vx += (wind - p.k * p.vx) * dt
        p.vy += (60 - p.k * p.vy) * dt
        p.x += p.vx * dt; p.y += p.vy * dt
        p.rot += p.spin * dt; p.flip += p.flipRate * dt
        if (p.y > H + 30 || p.x > W + 30) spawn(p)
        drawPetal(p)
      })
    }

    if (arc) {
      const e = (t - arcStart) / 1000
      const u = target * clamp(spring(e * 0.55, 6, 0.9), 0, 1.02)
      const [x, y] = bez(u)
      sun.setAttribute('cx', x); sun.setAttribute('cy', y)
      lit.style.strokeDashoffset = 1 - u
      // 每 6 秒一片花瓣从太阳处落下，沿抛物线 y = y0 + ½gτ²，x 匀速漂移
      const tau = (e % 6) / 6
      const px = x + 70 * tau, py = y + 120 * tau * tau
      fall.setAttribute('transform', `translate(${px} ${py}) rotate(${tau * 300})`)
      fall.style.opacity = py < 88 ? Math.sin(Math.PI * Math.min(1, tau * 1.3)) : 0
    }

    if (phone) {
      // 临界阻尼弹簧：a = ω²(target − x) − 2ωv
      const w = 7
      vx += (w * w * (tx - cx) - 2 * w * vx) * dt; cx += vx * dt
      vy += (w * w * (ty - cy) - 2 * w * vy) * dt; cy += vy * dt
      phone.style.transform = `rotateY(${cx}deg) rotateX(${cy}deg) translateY(${Math.sin(s * 0.8) * 6}px)`
    }

    requestAnimationFrame(frame)
  }
  requestAnimationFrame(frame)

  // 导航随滚动加深一点玻璃
  const nav = document.querySelector('[data-nav]')
  addEventListener('scroll', () => nav && nav.classList.toggle('scrolled', scrollY > 24), { passive: true })
})()
