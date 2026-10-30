# 拾日云（Dayweave Cloud）

商业版的服务端：用我们提供的 Google Cloud 密钥替用户转写和整理，按**有效转录时长**计费。源码版 App 不需要它，用户在 App 里填自己的密钥即可。

- 零依赖：Node 22.13+ 自带的 `node:sqlite` 和 `fetch`。
- 不保存音频和文字，只记账。
- 有效时长 = 服务端收到的音频长度。App 在手机上用 Silero VAD 去掉静音和环境音，听音乐、播客、看视频的声音不录，所以这些都不计费。

## 计价


| --- | --- | --- |
| **合计** | | **$0.0404 / 分钟有效人声（约 $2.42 / 小时）** |

价格核对于 2026-10-30。整理和主动提醒不单独收费，每个账户每天最多 200 次 AI 调用。

## 运行

```bash
cp .env.example .env   # 填服务账号路径
npm start
npm run admin -- codes --usd 5 --count 3   # 生成充值码
```

App 打包时指定服务地址：`./gradlew assembleRelease -PcloudUrl=https://cloud.example.com`。
本地联调：`adb reverse tcp:8787 tcp:8787`，再用 `-PcloudUrl=http://127.0.0.1:8787` 打包（调试包允许明文 HTTP 到本机）。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/v1/pricing` | 计价说明 |
| POST | `/v1/accounts` | 创建匿名账户，返回令牌，赠送体验额度 |
| GET | `/v1/me` | 余额、本月用量、最近账本 |
| POST | `/v1/redeem` | `{code}` 兑换充值码 |
| POST | `/v1/transcribe` | `{audio}` 16 kHz 16-bit 单声道 PCM 的 base64，≤ 60 秒；返回 Speech-to-Text v2 原始结果 |
| POST | `/v1/digest` | `{system, user, schema}`，返回 `{text}` |

尚未接入在线支付；目前用充值码。

## 线上部署（us-xl）

- 地址：`https://dayweave.duanjl.com`，经 Cloudflare Tunnel `dayweave` 暴露，服务只监听 `127.0.0.1:8787`，不暴露服务器 IP。
- 在 us-xl 上：`~/dayweave/{app,secrets,data,tunnel}`，tmux 会话 `dayweave`（窗口 `server` 跑 Docker 容器，`tunnel` 跑 cloudflared）。
- 更新：`rsync -a --delete server/src server/package.json server/Dockerfile us-xl:dayweave/app/`，然后在 us-xl 上 `docker build -t dayweave-cloud ~/dayweave/app` 并重启 `server` 窗口。
