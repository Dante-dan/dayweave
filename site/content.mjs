// 落地页文案：英文为默认（/），中文在 /zh/。两种语言结构一致，模板只认字段名。
// 价格与 server/src/plans.mjs 保持一致。

export const SITE = 'https://dayweave.duanjl.com'
export const REPO = 'https://github.com/Dante-dan/dayweave'
export const UPDATED = '2026-10-31'

export const PLANS = [
  { id: 'lite', usd: 19.99, yearly: 199.99, hours: 5 },
  { id: 'plus', usd: 99.99, yearly: 999.99, hours: 30, featured: true },
  { id: 'pro', usd: 199.99, hours: 70 },
  { id: 'max', usd: 499.99, hours: 180 },
]
/** 月付相对按量（授权码）的折扣；年付相对 12 个月月付的折扣。 */
export const saving = (p, yearly = false) => Math.round((1 - (yearly ? p.yearly / (12 * p.usd) : p.usd / (p.hours * 60 * PER_MINUTE_USD))) * 100)
export const CODE_USD = 20
export const PER_MINUTE_USD = 0.0808
const perDayMin = (h) => Math.round((h * 60) / 30)
const asHours = (m) => (m / 60).toFixed(1).replace(/\.0$/, '')

export const T = {
  en: {
    lang: 'en', htmlLang: 'en', prefix: '',
    title: 'Dayweave — an ambient secretary that remembers your day',
    description:
      'Dayweave quietly listens to everyday conversations, keeps a faithful text archive, and turns it into daily digests, reminders and insights. Audio is temporary; words stay.',
    keywords: 'ambient AI secretary, conversation transcription app, daily digest, personal agent, voice notes, meeting memory, Dayweave, 拾日',
    nav: { how: 'How it works', features: 'Features', privacy: 'Privacy', pricing: 'Pricing', faq: 'FAQ' },
    switchLang: { href: '/zh/', label: '中文' },
    hero: {
      eyebrow: 'Dayweave · 拾日',
      title: ['Pick up what', 'your day said.'],
      lead:
        'An ambient secretary for the things you say and hear. Dayweave listens quietly, writes a faithful text archive of your conversations, and turns it into a gentle digest, reminders and insights — without you pressing record.',
      primary: 'Get Dayweave for Android',
      secondary: 'Read the source',
      note: 'Android today · iPhone and Google Play coming soon',
    },
    phone: {
      greeting: 'Good morning',
      date: 'Saturday · blossoms of the morning',
      watching: 'Dayweave noticed',
      cardTime: 'Today 3:00 PM',
      cardTitle: 'Lakeside bookshop',
      cardBody: 'You agreed to meet at the bookshop by the lake this afternoon.',
      cardQuote: '“Sure — three o’clock, see you there!”',
      remind: '15 min before',
      add: 'Add to calendar',
      dayLabel: 'Yesterday',
      dayMeta: '12 conversations · 41 min',
      dayTitle: 'A slow Sunday of plans',
      dayBody: 'Talked through the trip to the coast, decided to leave on Friday, and promised Mia to bring the old film camera.',
      tabs: ['Days', 'Timeline', 'Capture'],
    },
    how: {
      title: 'Two quiet steps',
      lead: 'Recording faithfully and understanding thoughtfully are kept apart, so neither gets in the way of the other.',
      steps: [
        { k: '01', t: 'Listen, quietly', d: 'Your phone listens in the background. On-device voice detection keeps only human speech; silence, music, podcasts and video never leave the phone.' },
        { k: '02', t: 'Keep the words', d: 'Speech is transcribed in the cloud with speakers, times and sources, saved as text on your phone, and the audio is deleted right after.' },
        { k: '03', t: 'Weave the day', d: 'An agent reads the archive and writes what mattered: a daily digest, what to follow up, recurring themes, and a reminder when a promise has a time.' },
      ],
    },
    features: {
      title: 'Made to stay out of the way',
      items: [
        { icon: 'bell', t: 'Proactive, not pushy', d: 'When a conversation contains a time — “tomorrow at three” — a notification offers to set an alarm or add it to your calendar. One tap, or ignore.' },
        { icon: 'voice', t: 'Knows who is talking', d: 'Name a voice once and Dayweave recognises it on its own afterwards, on-device. Your own voice is enrolled with a short reading.' },
        { icon: 'leaf', t: 'A silent listener', d: 'No screen recording, no hijacked earphones, no call takeover. It uses the microphone gently and steps aside when you play music.' },
        { icon: 'globe', t: 'Bilingual by nature', d: 'Chinese, English and mixed speech in the same sentence. Digests keep the original language instead of forcing a translation.' },
        { icon: 'text', t: 'Text you can take with you', d: 'Your archive is plain text on your phone. Readable, searchable, portable — not a pile of recordings.' },
        { icon: 'key', t: 'Your keys or ours', d: 'Use your own Google Cloud and AI keys for free, or subscribe to Dayweave Cloud and skip the setup entirely.' },
      ],
    },
    privacy: {
      title: 'Audio is temporary. Words are yours.',
      points: [
        'Audio exists only long enough to be transcribed, then it is deleted.',
        'Transcripts and digests are stored on your phone, not on our servers.',
        'Dayweave Cloud keeps no audio and no text — only usage minutes for billing.',
        'No accounts, no ads, no tracking. Subscriptions are handled by Apple and Google.',
        'Recording is always visible and one tap away from pause. Please record only where everyone is comfortable with it.',
      ],
      link: 'Read the privacy policy',
    },
    pricing: {
      title: 'Pay for voice, not for silence',
      lead: 'Only speech that reaches the cloud counts. Silence, music, podcasts and videos are filtered on your phone and never billed. Daily digests and reminders are included in subscriptions; with a license code, AI features draw on the same balance.',
      monthly: '/ month',
      hours: (h) => `${h} hours of voice each month`,
      perDay: (h) => perDayMin(h) >= 60 ? `≈ ${asHours(perDayMin(h))} h of conversation a day` : `≈ ${perDayMin(h)} min of conversation a day`,
      names: { lite: 'Lite', plus: 'Plus', pro: 'Pro', max: 'Max' },
      blurbs: { lite: 'For the important talks of the week.', plus: 'About an hour of conversation every day.', pro: 'For long days of meetings and calls.', max: 'Listening from morning to dusk.' },
      featured: 'Most chosen',
      save: (n) => `Save ${n}% vs pay-as-you-go`,
      yearly: (usd, n) => `or $${usd} a year — 2 months free`,
      monthlyOnly: 'Monthly only',
      subscribe: 'Subscribe in the app',
      source: { name: 'Source edition', price: 'Free', d: 'Bring your own Google Cloud Speech and AI keys. Everything runs with your accounts.', cta: 'View on GitHub' },
      code: { name: 'Pay as you go', d: (usd, min) => `A $${usd} license code, about ${min} minutes of voice. No account, no renewal. Redeem it in the Android app.`, cta: 'License code' },
      fine: 'Prices in USD; your store shows local prices and taxes. Yearly plans cost 10 months and also refill every 30 days. When a month’s hours run out, cloud transcription pauses until the next refill.',
    },
    faq: {
      title: 'Questions',
      items: [
        ['Does Dayweave record everything I hear?', 'No. Voice activity detection runs on your phone and only speech segments are sent for transcription. Music, podcasts and video are noted as a scene but not recorded.'],
        ['Where is my data stored?', 'Transcripts and digests live on your phone. Audio is deleted as soon as it is transcribed. Dayweave Cloud stores only how many minutes were used, so it can bill correctly.'],
        ['Which languages are supported?', 'Chinese and English, including mixed speech in one conversation. The digest keeps each quote in the language it was spoken.'],
        ['Is there an iPhone version?', 'Dayweave for iPhone is on its way to the App Store. Android is available today.'],
        ['What happens when my monthly hours run out?', 'Capture keeps working; cloud transcription pauses until your hours refill. You can move to a larger plan at any time.'],
        ['Is Dayweave open source?', 'It is source-available under the PolyForm Strict license: you can read the code and use it personally, but not modify, redistribute or use it commercially.'],
      ],
    },
    footer: { privacy: 'Privacy', terms: 'Terms', support: 'Support', source: 'Source', made: 'Made with care, morning to dusk.' },
    pages: {
      privacy: { title: 'Privacy Policy', description: 'How Dayweave handles audio, transcripts and subscriptions.' },
      terms: { title: 'Terms of Use', description: 'Terms for using Dayweave and Dayweave Cloud subscriptions.' },
      support: { title: 'Support', description: 'Get help with Dayweave, subscriptions and license codes.' },
    },
    updated: 'Last updated',
    back: 'Back to Dayweave',
  },

  zh: {
    lang: 'zh', htmlLang: 'zh-CN', prefix: '/zh',
    title: '拾日 Dayweave — 从日常对话中，拾起值得记住的事',
    description:
      '拾日是一位安静的随身秘书：在你说话时静静旁听，把对话转成可靠的文字档案，再整理成每日小结、提醒和洞察。录音只是临时材料，文字才长期留下。',
    keywords: '拾日, Dayweave, AI 秘书, 对话转写, 录音转文字, 每日总结, 个人助理, 会议记录, 语音备忘',
    nav: { how: '工作方式', features: '特性', privacy: '隐私', pricing: '价格', faq: '常见问题' },
    switchLang: { href: '/', label: 'English' },
    hero: {
      eyebrow: '拾日 · Dayweave',
      title: ['朝花夕拾，', '拾起这一天。'],
      lead:
        '一位安静的随身秘书。拾日在你说话时静静旁听，把对话写成可靠的文字档案，再整理成温柔的每日小结、提醒和洞察。不用按录音键，也不用补笔记。',
      primary: '下载安卓版',
      secondary: '查看源码',
      note: '安卓版已可用 · iPhone 版与 Google Play 即将上线',
    },
    phone: {
      greeting: '早上好，这是你的拾日',
      date: '星期六 · 朝花未落，今天慢慢来',
      watching: '拾日为你留意',
      cardTime: '今天 15:00',
      cardTitle: '湖边书店见面',
      cardBody: '对方约你今天下午三点在湖边的书店见面，你已答应。',
      cardQuote: '“好呀，三点书店见！”',
      remind: '提前 15 分钟',
      add: '加到日程',
      dayLabel: '昨天',
      dayMeta: '12 段对话 · 41 分钟',
      dayTitle: '慢悠悠的周日，定下了海边之行',
      dayBody: '聊了去海边的计划，决定周五出发；答应小米带上那台老胶片相机。',
      tabs: ['拾日', '时间线', '采集'],
    },
    how: {
      title: '安静的两步',
      lead: '忠实记录和用心整理分开进行，彼此不打扰。',
      steps: [
        { k: '01', t: '静静旁听', d: '手机在后台旁听，本地的人声检测只留下说话声；静音、音乐、播客和视频都不会离开手机。' },
        { k: '02', t: '留下文字', d: '人声在云端转写，标注说话人、时间和来源，以文字保存在手机上，音频随即删除。' },
        { k: '03', t: '织成一天', d: 'Agent 阅读文字档案，写下真正重要的：每日小结、要跟进的事、反复出现的主题；对话里约了时间，就提醒你。' },
      ],
    },
    features: {
      title: '尽量不打扰你',
      items: [
        { icon: 'bell', t: '主动，但不聒噪', d: '对话里出现“明天下午三点”这样的约定，通知里一键设闹钟或加到日程；不需要就忽略。' },
        { icon: 'voice', t: '认得谁在说话', d: '给一个声音标注一次名字，之后在手机上按声纹自动认出。你自己的声音读一小段就能录入。' },
        { icon: 'leaf', t: '安静的旁听者', d: '不录屏，不抢耳机，不接管通话。温和地使用麦克风，你放音乐时自动让开。' },
        { icon: 'globe', t: '天生双语', d: '中文、英文以及一句话里的中英混说。小结保留原话的语言，不强行翻译。' },
        { icon: 'text', t: '带得走的文字', d: '档案是手机上的纯文字：可读、可搜、可迁移，而不是一堆录音。' },
        { icon: 'key', t: '自带密钥，或用拾日云', d: '用自己的 Google Cloud 和 AI 密钥免费使用；或订阅拾日云，什么都不用配置。' },
      ],
    },
    privacy: {
      title: '音频只是过客，文字属于你。',
      points: [
        '音频只保留到转写完成，随即删除。',
        '转写和整理结果保存在你的手机上，不在我们的服务器上。',
        '拾日云不保存音频，也不保存文字，只记录用于计费的时长。',
        '没有账号，没有广告，没有追踪。订阅由 Apple 和 Google 处理。',
        '录音状态始终可见，一键暂停。请只在大家都愿意被记录的场合使用。',
      ],
      link: '阅读隐私政策',
    },
    pricing: {
      title: '为人声付费，沉默不收钱',
      lead: '只有送到云端的人声才计费。静音、音乐、播客和视频在手机上就被过滤，不收费。订阅已包含每日整理和主动提醒；授权码的 AI 功能从同一份余额里扣。',
      monthly: '/ 月',
      hours: (h) => `每月 ${h} 小时人声`,
      perDay: (h) => perDayMin(h) >= 60 ? `约每天 ${asHours(perDayMin(h))} 小时对话` : `约每天 ${perDayMin(h)} 分钟对话`,
      names: { lite: '轻享', plus: '标准', pro: '专业', max: '全天' },
      blurbs: { lite: '一周里那些重要的谈话。', plus: '每天约一小时的对话。', pro: '会议和通话很多的长日子。', max: '从清晨听到黄昏。' },
      featured: '最多人选',
      save: (n) => `比按量省 ${n}%`,
      yearly: (usd, n) => `年付 $${usd}，送 2 个月`,
      monthlyOnly: '仅月付',
      subscribe: '在 App 内订阅',
      source: { name: '源码版', price: '免费', d: '自带 Google Cloud 转写和 AI 密钥，一切都跑在你自己的账号上。', cta: '在 GitHub 查看' },
      code: { name: '按量付费', d: (usd, min) => `$${usd} 一个授权码，约 ${min} 分钟人声。无需注册，不自动续费，在安卓 App 内兑换。`, cta: '授权码' },
      fine: '以美元标价，应用商店会显示当地价格和税费。年付按 10 个月计价，同样每 30 天发放一次时长。当月时长用完后，云端转写暂停到下次发放。',
    },
    faq: {
      title: '常见问题',
      items: [
        ['拾日会录下我听到的所有声音吗？', '不会。人声检测在手机上运行，只有说话片段会送去转写。音乐、播客和视频只记下场景，不录音。'],
        ['我的数据存在哪里？', '转写和整理结果都在你的手机上。音频转写完立即删除。拾日云只记录用了多少分钟，用于计费。'],
        ['支持哪些语言？', '中文和英文，包括同一段对话里的中英混说。小结里的引语保留说话时的语言。'],
        ['有 iPhone 版吗？', 'iPhone 版正在上架 App Store 的路上，安卓版现在就能用。'],
        ['每月时长用完了怎么办？', '采集照常进行，云端转写暂停到下次发放时长。也可以随时换到更大的方案。'],
        ['拾日是开源的吗？', '拾日以 PolyForm Strict 许可证公开源码：可以阅读、个人使用，但不能修改、再分发或商用，不属于 OSI 定义的开源。'],
      ],
    },
    footer: { privacy: '隐私政策', terms: '服务条款', support: '支持', source: '源码', made: '从清晨到黄昏，用心制作。' },
    pages: {
      privacy: { title: '隐私政策', description: '拾日如何处理音频、文字和订阅。' },
      terms: { title: '服务条款', description: '使用拾日与拾日云订阅的条款。' },
      support: { title: '支持', description: '拾日、订阅与授权码的帮助。' },
    },
    updated: '最近更新',
    back: '返回拾日',
  },
}

// 法律页正文（Markdown 子集：## 标题、段落、- 列表）。
export const LEGAL = {
  en: {
    privacy: `
Dayweave ("we") is built so that as little of your life as possible ever reaches us. This policy covers the Dayweave apps for Android and iOS, the website, and Dayweave Cloud.

## What the app collects
- **Audio** from the microphone while capture is on. On-device voice activity detection keeps only speech; silence, music, podcasts and video are not recorded.
- **Transcripts, digests and reminders** generated from that speech, stored on your device.
- **Voice profiles** (numeric voice embeddings) for people you name, stored on your device.

## Where audio and text go
- **Source edition:** speech is sent to Google Cloud Speech-to-Text and, for digests, to the AI provider you configure, using your own keys. Their terms apply.
- **Dayweave Cloud:** speech is sent to our server, which forwards it to Google Cloud Speech-to-Text and Google Vertex AI and returns the result. Our server does not store audio or text. It records only the duration processed, the time, and a one-way hash of your license or subscription identifier, for billing and abuse prevention.
- Audio on your device is deleted once its transcript is saved.

## Subscriptions and license codes
- Subscriptions are processed by Apple (App Store) or Google (Google Play). We never see your payment details. To confirm a subscription, the app sends us the store's transaction or purchase token, which we verify with Apple or Google and store with its usage balance.
- License codes are stored on our server only as SHA-256 hashes.

## What we do not do
- No accounts, no advertising, no third-party analytics or tracking SDKs.
- We do not sell or share your data, and we do not use your audio or text to train models.

## Your choices
- Pause capture at any time from the app or notification.
- Delete conversations, days or the entire archive inside the app; uninstalling removes all local data.
- Ask us to delete billing records tied to a subscription or license code via the support page.

## Recording others
Please record only where the people around you are aware and comfortable, and follow the laws where you are. Dayweave shows when it is listening.

## Children
Dayweave is not directed to children under 13.

## Changes and contact
We will update this page when practices change. Questions: see the support page.
`,
    terms: `
## Using Dayweave
Dayweave is provided as is. Transcriptions and AI digests can be wrong; please verify anything important. You are responsible for using Dayweave lawfully, including obtaining consent where recording requires it.

## Dayweave Cloud subscriptions
- Subscriptions (Lite, Plus, Pro, Max; monthly or yearly) renew automatically through the App Store or Google Play until cancelled. Manage or cancel them in your store account settings at least 24 hours before renewal.
- Each plan includes a monthly amount of transcribed voice; yearly plans refill it every 30 days. Unused hours do not roll over. When the included hours are used up, cloud transcription pauses until the next refill.
- Digests, reminders and assistant chats are included, subject to a fair-use limit of 80 AI requests per day.
- Refunds are handled by Apple or Google under their policies.

## License codes
A license code carries a fixed prepaid balance that is consumed by transcribed voice and by AI features (digests, reminders, assistant chats). Codes do not expire and cannot be topped up or refunded once used. Keep your code private — whoever holds it can use it.

## Source code
The Dayweave source is published under the PolyForm Strict License 1.0.0. It allows reading and personal non-commercial use, but not modification, redistribution or commercial use.

## Availability
We aim to keep Dayweave Cloud available but cannot guarantee uninterrupted service. We may change prices with notice; changes to subscriptions take effect at renewal as required by the stores.

## Liability
To the extent permitted by law, we are not liable for indirect or consequential damages, and our total liability is limited to the amount you paid for Dayweave Cloud in the previous three months.
`,
    support: `
## Getting help
Open an issue on GitHub — we read every one: [github.com/Dante-dan/dayweave/issues](${REPO}/issues). Please do not post license codes or transcripts publicly.

## Subscriptions
- **Cancel or change plan:** iPhone — Settings › your name › Subscriptions. Android — Google Play › Payments & subscriptions › Subscriptions.
- **Restore on a new phone:** open Dayweave › Capture › Service › Restore purchases, signed in to the same Apple ID or Google account.
- **Refunds:** requested from Apple or Google directly.

## License codes
Enter a code in Dayweave › Capture › Service › Dayweave Cloud. The remaining balance and recent usage are shown there.

## Deleting your data
Everything Dayweave keeps about your life is on your phone; delete it in the app or uninstall. To remove the billing record of a subscription or code, open an issue asking for deletion and we will tell you how to verify it privately.
`,
  },
  zh: {
    privacy: `
拾日（“我们”）的设计目标是：你的生活尽可能少地到达我们这里。本政策适用于安卓和 iOS 上的拾日 App、本网站以及拾日云。

## App 收集什么
- **音频**：采集开启时来自麦克风。手机上的人声检测只保留说话声；静音、音乐、播客和视频不会被录下。
- **转写、整理和提醒**：由这些人声生成，保存在你的设备上。
- **声纹**：你标注了名字的人的声纹特征（一组数字），保存在你的设备上。

## 音频和文字去哪里
- **源码版**：人声发送到 Google Cloud Speech-to-Text，整理发送到你配置的 AI 服务，使用你自己的密钥，适用它们的条款。
- **拾日云**：人声发送到我们的服务器，由服务器转交 Google Cloud Speech-to-Text 和 Google Vertex AI 并返回结果。我们的服务器不保存音频和文字，只记录处理时长、时间，以及授权码或订阅标识的单向哈希，用于计费和防滥用。
- 设备上的音频在转写结果保存后即删除。

## 订阅与授权码
- 订阅由 Apple（App Store）或 Google（Google Play）处理，我们看不到你的支付信息。为确认订阅，App 会把商店的交易或购买凭证发给我们，我们向 Apple 或 Google 核验后，连同用量余额一起保存。
- 授权码在服务器上只以 SHA-256 哈希保存。

## 我们不做的事
- 没有账号，没有广告，没有第三方统计或追踪 SDK。
- 不出售或共享你的数据，不用你的音频或文字训练模型。

## 你的选择
- 随时在 App 或通知中暂停采集。
- 在 App 内删除对话、某一天或整个档案；卸载 App 会删除所有本地数据。
- 通过支持页请求删除与订阅或授权码相关的计费记录。

## 记录他人
请只在身边的人知情且愿意的场合记录，并遵守当地法律。拾日会明确显示正在旁听。

## 儿童
拾日不面向 13 岁以下儿童。

## 变更与联系
做法变化时我们会更新本页。有问题请见支持页。
`,
    terms: `
## 使用拾日
拾日按现状提供。转写和 AI 整理可能出错，重要内容请自行核对。你需要合法使用拾日，包括在需要时取得被录音者的同意。

## 拾日云订阅
- 订阅（轻享、标准、专业、全天；月付或年付）通过 App Store 或 Google Play 自动续费，直到取消。请至少在续费前 24 小时在商店账号设置中管理或取消。
- 每个方案包含每月一定时长的人声转写，年付每 30 天发放一次。未用完的时长不结转。时长用完后，云端转写暂停到下次发放。
- 每日整理、提醒和助手对话包含在内，合理使用上限为每天 80 次 AI 请求。
- 退款由 Apple 或 Google 按其政策处理。

## 授权码
授权码带有固定的预付额度，按转写的人声时长和 AI 功能（整理、提醒、助手对话）的用量扣减。授权码不过期，不能充值，使用后不退款。请妥善保管：持有授权码的人都可以使用它。

## 源码
拾日源码以 PolyForm Strict License 1.0.0 发布：允许阅读和个人非商业使用，不允许修改、再分发或商用。

## 可用性
我们尽力保持拾日云可用，但不保证不中断。我们可能在通知后调整价格；订阅价格变更按商店要求在续费时生效。

## 责任
在法律允许的范围内，我们不对间接或后果性损失负责，总责任以你在过去三个月为拾日云支付的金额为限。
`,
    support: `
## 获取帮助
在 GitHub 提 issue，我们会逐条阅读：[github.com/Dante-dan/dayweave/issues](${REPO}/issues)。请不要公开贴出授权码或转写内容。

## 订阅
- **取消或更换方案**：iPhone — 设置 › 你的名字 › 订阅；安卓 — Google Play › 付款和订阅 › 订阅。
- **在新手机上恢复**：打开拾日 › 采集 › 服务 › 恢复购买，并登录同一个 Apple ID 或 Google 账号。
- **退款**：直接向 Apple 或 Google 申请。

## 授权码
在 拾日 › 采集 › 服务 › 拾日云 中输入授权码，这里会显示剩余额度和最近用量。

## 删除数据
拾日保存的关于你生活的一切都在你的手机上，可以在 App 内删除或直接卸载。如需删除订阅或授权码的计费记录，请提 issue 说明，我们会告诉你如何私下核验。
`,
  },
}
