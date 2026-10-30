# 拾日云（Dayweave Cloud）

商业版的服务端：用我们提供的 Google Cloud 密钥替用户转写和整理，按**有效转录时长**计费。源码版 App 不需要它，用户在 App 里填自己的密钥即可。

- 零依赖：Node 22.13+ 自带的 `node:sqlite` 和 `fetch`。
- 不保存音频和文字，只记账。
- 有效时长 = 服务端收到的音频长度。App 在手机上用 Silero VAD 去掉静音和环境音，听音乐、播客、看视频的声音不录，所以这些都不计费。

## 计价


| --- | --- | --- |
| **合计** | | **$0.0404 / 分钟有效人声（约 $2.42 / 小时）** |

价格核对于 2026-10-30。整理和主动提醒不单独收费，每个授权码每天最多 120 次 AI 调用。

## 授权码

没有用户体系：**授权码就是使用权**。每个授权码有固定额度（例如 $5），按有效转录时长扣费，用完即止，不能充值，换一个新码即可。

- 服务端只保存授权码的 SHA-256 和余额，不保存明文；即使数据库泄露也拿不到可用的码。
- 授权码 `DW-XXXXX-XXXXX-XXXXX-XXXXX`：Crockford Base32，100 bit 随机熵，在**本地**生成（`server/local/`，不进仓库），明文只写在本机 `server/local/codes.csv`（权限 600）。
- 同一来源一小时内输错 20 次即拒绝（429），防止猜码。

发码（明文留在本地，只把哈希导入服务器）：

```bash
node server/local/issue-codes.mjs --count 10 --usd 5 --label sale-2026-10-30 \
  | ssh us-xl 'docker exec -i dayweave-cloud node src/import-licenses.mjs'
```

## 运行

```bash
cp .env.example .env   # 填服务账号路径
npm start
```

App 打包时指定服务地址：`./gradlew assembleRelease -PcloudUrl=https://…`，或在 `android/local.properties` 里写 `cloudUrl=…`（不进仓库）。

## 接口

除了计价说明，所有接口都要 `Authorization: Bearer <授权码>`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/v1/pricing` | 计价说明（公开） |
| GET | `/v1/me` | 授权码的额度、余额、本月用量、最近记录 |
| POST | `/v1/transcribe` | `{audio}` 16 kHz 16-bit 单声道 PCM 的 base64，≤ 60 秒；返回 Speech-to-Text v2 结果；按秒扣费 |
| POST | `/v1/assist` | `{kind: digest \| agent, lang: zh \| en, input}`；提示词与输出结构由服务端保管（`src/prompts.mjs`），不能当通用大模型代理；每个码每天最多 120 次 |

## 线上部署（us-xl）

- 地址：`https://dayweave.duanjl.com`，经独立的 Cloudflare Tunnel `dayweave` 暴露；服务只监听 `127.0.0.1:8787`，不暴露服务器 IP。
- 目录：`~/dayweave/{app,secrets,data,tunnel}` 和 `~/dayweave/.env`（权限 600，服务账号路径为容器内的 `/secrets/google-sa.json`）。
- 开机自启：
  - 服务：Docker 容器 `dayweave-cloud`，重启策略 `unless-stopped`，随 Docker 启动。
  - 隧道：systemd 服务 `dayweave-tunnel`（`/etc/systemd/system/dayweave-tunnel.service`），与机器上原有的 `cloudflared` 服务互不影响。
- 更新代码：

  ```bash
  rsync -a --delete server/src server/package.json server/Dockerfile us-xl:dayweave/app/
  ssh us-xl 'cd ~/dayweave/app && docker build -t dayweave-cloud . && docker rm -f dayweave-cloud && \
    docker run -d --name dayweave-cloud --restart unless-stopped -p 127.0.0.1:8787:8787 \
      -v /root/dayweave/data:/app/data -v /root/dayweave/secrets:/secrets:ro --env-file /root/dayweave/.env dayweave-cloud'
  ```
- 日志：`docker logs -f dayweave-cloud`、`journalctl -u dayweave-tunnel -f`。
