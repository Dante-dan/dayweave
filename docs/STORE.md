# 上架材料

App Store 与 Google Play 共用的文案、订阅商品和审核说明。注册开发者账号、签署协议后照此填写。

## 基本信息

| 项 | App Store | Google Play |
| --- | --- | --- |
| 名称 | Dayweave · 拾日 | Dayweave · 拾日 |
| 包名 / Bundle ID | `app.dayweave` | `app.dayweave` |
| 类别 | 效率 | 效率 |
| 隐私政策 | https://dayweave.duanjl.com/privacy/ | 同左 |
| 使用条款（EULA） | https://dayweave.duanjl.com/terms/ | 同左 |
| 支持网址 | https://dayweave.duanjl.com/support/ | 同左 |
| 营销网址 | https://dayweave.duanjl.com/ | — |
| 年龄分级 | 4+（无用户生成内容公开、无广告） | 所有人 |

## 副标题 / 简短说明（≤ 30 / 80 字符）

- EN：An ambient secretary for your day
- ZH：安静的随身秘书，拾起值得记住的事
- Play 简短说明 EN：Quietly turns everyday conversations into a text diary, digests and reminders.

## 描述

**EN**

Dayweave is an ambient secretary for the things you say and hear.

Turn it on and live your day. Dayweave listens quietly, keeps only human speech, and writes a faithful text archive of your conversations — who spoke, when, and what was said. Audio is deleted right after transcription; your words stay on your phone.

Every day it writes a gentle digest: what happened, what was decided, what you promised to follow up, and patterns worth noticing. When a conversation contains a time — "see you tomorrow at three" — Dayweave offers to remind you or add it to your calendar.

• Silent listener: no earphone takeover, music keeps playing, podcasts and videos are never recorded
• Chinese, English and mixed speech in one sentence
• Daily digests, follow-ups and insights, quoted in the original language
• Proactive reminders and calendar events
• No accounts, no ads, no tracking

Dayweave Cloud subscriptions include a monthly allowance of transcribed voice. Only speech counts.

**ZH**

拾日是一位安静的随身秘书。

打开后照常生活。拾日静静旁听，只留下人声，把对话写成可靠的文字档案：谁说的、什么时候、说了什么。音频转写后立即删除，文字保存在你的手机上。

每天，它会写一份温柔的小结：发生了什么、做了哪些决定、答应了要跟进的事、值得留意的变化。对话里约了时间——“明天下午三点见”——拾日会提醒你，或者帮你加到日历。

• 安静旁听：不抢耳机，音乐照常播放，播客和视频不录
• 中文、英文，以及一句话里的中英混说
• 每日小结、待跟进和洞察，引语保留原话
• 主动提醒与日程
• 没有账号，没有广告，没有追踪

拾日云订阅每月包含一定时长的人声转写，只计人声。

## 关键词（App Store，≤ 100 字符）

- EN：`transcribe,diary,journal,secretary,assistant,meeting,notes,voice,reminder,digest,memory,AI`
- ZH：`拾日,录音转文字,日记,秘书,助理,会议记录,语音笔记,提醒,总结,备忘,AI`

## 订阅商品

一个订阅组“Dayweave Cloud”，同组内可升降级（排序从高到低：全天月付 → 轻享月付）。商品 ID 与服务端 `server/src/plans.mjs` 一致。Google Play 上每个商品建一个基础方案。年付 = 10 个月价格；专业和全天只有月付（App Store 订阅单价上限 $1,000）。

| 商品 ID | 名称 | 周期 | 价格（USD） |
| --- | --- | --- | --- |
| `app.dayweave.cloud.lite.monthly` | 拾日云 轻享 · 每月 5 小时 | 1 个月 | 19.99 |
| `app.dayweave.cloud.lite.yearly` | 拾日云 轻享（年付） | 1 年 | 199.99 |
| `app.dayweave.cloud.plus.monthly` | 拾日云 标准 · 每月 30 小时 | 1 个月 | 99.99 |
| `app.dayweave.cloud.plus.yearly` | 拾日云 标准（年付） | 1 年 | 999.99 |
| `app.dayweave.cloud.pro.monthly` | 拾日云 专业 · 每月 100 小时 | 1 个月 | 199.99 |
| `app.dayweave.cloud.max.monthly` | 拾日云 全天 · 每月 280 小时 | 1 个月 | 499.99 |

## 服务端配置

- App Store：服务端离线核验签名交易，无需额外密钥。可选：在 App Store Connect 填写 App Store Server Notifications V2 地址，以后用于退款即时停用。
- Google Play：在 Play 管理中心 › 用户和权限 › 邀请服务账号（“查看财务数据、订单和取消订阅调查问卷”“管理订单和订阅”），服务端 `.env` 设 `GOOGLE_PLAY_SERVICE_ACCOUNT_FILE`（不设则用现有服务账号）。

## 隐私标签 / 数据安全

- 收集：音频（仅为转写临时上传，不保存）、用户内容（转写文字仅保存在设备上）、购买记录（商店交易标识，用于开通订阅）。
- 不用于追踪，不与第三方共享用于广告；不关联身份（无账号）。
- 传输加密：是。可删除：是（App 内删除，卸载即清空）。

## 审核说明（App Review Notes）

Dayweave listens through the built-in microphone while capture is on (background audio mode is used only to keep listening; the microphone indicator stays visible). Voice segments are sent to our server for transcription and deleted on the device afterwards. To test: open Capture › Dayweave Cloud, subscribe to any plan in the sandbox, return to Capture and tap the orb, then speak for a few seconds (for example "Let's meet tomorrow at three at the bookshop"). The conversation appears in Timeline within about 10 seconds, and a reminder suggestion appears on Today.

## 构建

- Android：`cd android && ./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`（上传密钥在仓库外，见 `android/app/build.gradle.kts`）。首次上传时在 Play 选择“使用自己的应用签名密钥”并上传同一把密钥，这样官网 APK 与 Play 版本可以互相覆盖安装。
- iOS：`cd ios && xcodegen generate && xcodebuild -scheme Dayweave -configuration Release archive`，团队 97M5J4K9WG。需要先在 App Store Connect 签署最新的开发者协议和付费 App 协议。
