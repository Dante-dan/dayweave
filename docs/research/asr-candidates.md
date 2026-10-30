# ASR 候选初筛

状态：初筛，2026-10-30。以下信息来自厂商页面和第三方汇总，**尚未用真实采集样本测试，也未核实各家数据处理条款**。价格以官网为准。

决定（2026-10-30）：只使用云端 ASR，不使用本地转写模型。

## 选型条件

来自 [产品定位](../PRODUCT.md)：

1. 中英双语：纯中文、纯英文，以及同一句话内中英混说（code-switching）都要识别准确，英文术语和人名不能被音译成中文。这是硬性条件。
2. 说话人分段（diarization），没有可靠身份时用匿名标记。
3. 非实时的录音文件识别即可：单段最长 15 分钟，断网后补传。
4. 能接受通话链路的 16 kHz（甚至 8 kHz）窄带音频，这取决于 [audio-probe](../../spikes/audio-probe/README.md) 的结果。
5. 不长期保存音频：需要零留存选项，或者有明确且较短的留存期。
6. 个人使用规模的费用。全天有效讲话约 2–4 小时，按每月 100 小时估算。

## 候选

| 候选 | 中文（中英混说待测） | 说话人分段 | 留存 | 公开价格线索 | 备注 |
| --- | --- | --- | --- | --- | --- |
| **Google Cloud Speech-to-Text**（项目 `gen-lang-client-0050324770`） | v1 `latest_long` 不满足要求；**v2 Chirp 3 中英混说表现良好**（见下） | 待测 | 待核实 | 待查 | **用户指定的首选**。v2 不接受 API 密钥，需要服务账号（`roles/speech.client`） |
| 阿里云百炼 `qwen-audio-3.1-asr-flash-filetrans` | 强，中文是主场 | 支持 | 待核实 | 社区称批处理约 ¥0.10/小时（非官方） | 支持热词和 Prompt 上下文；单文件最长 12 小时 |
| 阿里云百炼 `fun-asr` / `paraformer-8k-v2` | 强 | 支持 | 待核实 | 待查 | `paraformer-8k-v2` 面向 8 kHz 电话音频，可能适合 SCO 窄带 |
| ElevenLabs Scribe v2 | 普通话可用，但不在错误率最低的一档 | 支持 | 有零留存模式（可能需要企业方案） | 约 $0.22/小时 | 境外服务 |
| OpenAI `gpt-4o-transcribe-diarize` | 好 | 支持，可用参考片段匹配已知说话人 | 零留存是否覆盖该模型待确认 | 待查 | 已知说话人匹配对“匿名标记”原则有影响，需要谨慎使用 |
| AssemblyAI / Deepgram | 中文是否为强项待测 | 支持 | 待核实 | 汇总文章给出的价格差异较大 | 第二梯队备选 |

国内的腾讯云、火山引擎（豆包）、科大讯飞也提供录音文件识别，这次初筛没有查到可靠的分离和价格信息，后续补充。

## Google v1 第一轮结果（2026-10-30）

用 API 密钥调用 v1 `speech:recognize`，模型 `latest_long`，`languageCode=cmn-Hans-CN`，`alternativeLanguageCodes=[en-US]`。测试句为电脑朗读的“大家好，这是 Dayweave 的麦克风测试，我们下周 review 一下这个 feature。This is a microphone level test for the probe.”

| 采集条件 | 识别结果 |
| --- | --- |
| 耳机 `bt_sco` + `MIC` | 大家好，这是对user麦克风测试，我们下周reco下这个fitman。（英文句子丢失） |
| 手机 `builtin_mic` + `MIC` | 大家好，这是这一位的麦克风测试，我们下周reco下这个feature This is a microphone not working。 |
| 手机 `builtin_mic` + `VOICE_COMMUNICATION` | 判定为 en-US：Here with the microphone. So she will review feature. / This is a microphone level test for the probe. （中文丢失） |

结论：v1 每段结果只选一种语言，中英混说时英文术语被误识别，或者整段另一种语言丢失，**不满足中英双语条件**。下一步测试 v2 Chirp 3（多语言 `languageCodes`）。

## Google v2 Chirp 3 第一轮结果（2026-10-30）

调用 `us-speech.googleapis.com` v2 `recognize`，模型 `chirp_3`，`languageCodes=[cmn-Hans-CN, en-US]`，显式指定 16 kHz 单声道 LINEAR16，用服务账号认证。测试句与 v1 相同。

| 采集条件 | 识别结果 |
| --- | --- |
| 耳机 `bt_sco` + `MIC` | 大家好,这是对麦克风测试,我们下周 review 一下这个 feature 。 This is a microphone level test for the probe. |
| 手机 `builtin_mic` + `MIC` | 大家好,这是麦克风测试,我们下周 review 一下这个 feature 。 This is a microphone level test for the probe. |
| 手机 `builtin_mic` + `VOICE_COMMUNICATION` | 大家好,这是麦克风测试,我们下周 review 一下这个 feature 。 This is a microphone level test for the probe. |

- 句内中英混说（review、feature）和整句英文都识别正确，三种采集条件结果几乎一致，说明耳机 SCO 窄带和系统降噪都没有明显影响识别。
- 唯一的错误是产品名 **Dayweave** 每次都被漏掉（耳机条件下变成“对”）。专有名词需要靠 Speech Adaptation（短语提示）或用户词表解决，待测。
- 用户自录的 6 段日常样本也都识别出了内容，其中一部分像是环境中的歌声或背景音，正式使用时需要区分“背景播放的内容”和“真人对话”。
- v2 同步识别不返回每段的时间偏移（`resultEndOffset` 为空），说话人分段也还没有测试。15 分钟片段需要改用 `BatchRecognize`。

结论：**Chirp 3 满足中英双语这一硬性条件**，作为首选候选继续评估。

## 下一步

1. 等 audio-probe 产出真实样本（耳机 SCO 和手机口袋各一组，双人交谈；纯中文、纯英文、中英混说各若干段），作为固定评测集，**只在本地保存**。
2. Google Chirp 3 已通过双语测试（见上）。接下来测试它的说话人分段、15 分钟 `BatchRecognize`、专有名词提示和费用；如果还需要对照，再测阿里云 `qwen-audio-3.1-asr-flash-filetrans` 和 `fun-asr`，以及 ElevenLabs Scribe，比较混合错误率（中文按字、英文按词）、中英混说的准确度、说话人分段准确度和对窄带音频的耐受度。要确认是否需要预先指定语言；如果必须指定单一语言，就不满足条件。
3. 逐家阅读数据处理条款，确认音频留存期、是否用于训练、能否关闭。
4. 根据结果在 `docs/` 中记录选型决定。

## 来源

- [阿里云百炼：语音识别模型](https://help.aliyun.com/zh/model-studio/asr-model)
- [千问 AI 平台：语音识别模型](https://platform.qianwenai.com/docs/developer-guides/speech/speech-to-text-models)
- [type4me #364：ASR 选型与成本对比（社区）](https://github.com/joewongjc/type4me/issues/364)
- [ElevenLabs Speech to Text API](https://elevenlabs.io/speech-to-text-api)
- [Speech-to-Text APIs in 2026（第三方汇总）](https://futureagi.substack.com/p/speech-to-text-apis-in-2026-benchmarks)
- [AssemblyAI benchmarks](https://www.assemblyai.com/benchmarks)
