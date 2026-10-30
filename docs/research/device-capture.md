# 设备收音链路调查

状态：进行中，2026-10-30 开始。

每条结论标注证据等级：

- **代码线索**：从 APK、协议或文档中读到的内容，说明“可能可以”。
- **运行观察**：在设备上观察到的日志、路由和行为。
- **真机验证**：用 audio-probe 或正式 App 在目标设备上实际跑通，并有日志为证。

## 设备

| 设备 | 系统/固件 | 角色 |
| --- | --- | --- |
| Redmi Buds 8（系统显示为 REDMI Buds 8） | 固件待记录 | 第一优先级输入 |
| 小米手环 9（Xiaomi Smart Band 9） | 固件待记录 | 第二优先级输入 |
| 小米 13 Ultra（2304FPN6DC） | HyperOS OS3.0.308.0.WMACNXM，Android 16（SDK 36） | App 宿主、兜底输入 |

## Redmi Buds 8

方法：[audio-probe](../../spikes/audio-probe/README.md)。

| 问题 | 结论 | 证据等级 |
| --- | --- | --- |
| 暴露为 `bt_sco` 还是 `ble_headset` 通话设备？ | 双模设备；已启用 A2DP、HEADSET（HFP）、HID，LE_AUDIO 未启用，并且是当前活跃 HFP 设备。预计走 `bt_sco` | 运行观察（2026-10-30，`dumpsys bluetooth_manager`） |
| 第三方 App 能否建立并保持采集会话？ | 能。`setCommunicationDevice(bt_sco)` 成功，`AudioRecord` 路由到 `bt_sco:REDMI Buds 8`，8 分钟内实际采样数与理论值基本一致（每 10 秒窗口偏差 < 0.5%）。只在路由切换时丢失约 0.8 秒 | 真机验证（2026-10-30 第 1 轮，8 分钟） |
| 锁屏、口袋中能持续多久？有无被系统挂起？ | 第 1 轮几乎全程亮屏（48 个窗口中只有 2 个熄屏），熄屏的 20 秒内采集正常。**用户决定（2026-10-30）：不做长时间锁屏测试，直接视为可用。** 这是产品决定，不是实测结论；正式 App 仍需记录锁屏中断 | 用户决定 |
| 能否听清对方说话？ | 待云端转写确认。电平偏低：多数窗口平均值为 −60 到 −65 dBFS，峰值很少超过 −40 dBFS；起始 30 秒样本中只有 2% 的帧高于 −40 dBFS。可能是测试时说话不多，也可能是 SCO 链路上的降噪或增益偏低 | 运行观察 |
| 放回充电盒、来电、播放音乐后能否恢复？ | **来电**：`audio_mode` 依次变为 1（响铃）、2（通话），通话设备切到听筒，本 App 收到 52%–75% 的全零帧，即系统把通话期间的并发采集静音；通话结束后，本 App 重新路由，约 25 秒后恢复 `bt_sco`。**放回充电盒**：耳机设备被移除，录音自动回落到 `builtin_mic`，采集不中断；约 2 分钟后耳机重新连接，自动切回 `bt_sco`。播放音乐待测 | 真机验证（第 1 轮） |
| 相比手机麦克风，每小时额外耗电多少？ | 第 1 轮手机接着 USB，电流读数为充电电流，无法使用。需要拔线测试 | — |

## 小米手环 9（小爱版）

方法：用 REA + ADB 从手机导出相关 APK 静态分析，再做运行观察。手机上已确认的相关包：`com.mi.health`（小米运动健康）、`com.miui.voiceassist`（小爱同学）、`com.miui.voicetrigger`、`com.xiaomi.aiasst.service`、`com.xiaomi.bluetooth`、`com.mi.earphone`（小米耳机）。

| 问题 | 结论 | 证据等级 |
| --- | --- | --- |
| 手环的语音经过什么链路到手机（BLE 私有协议、经典蓝牙）？编码格式是什么？ | 双模绑定，但只启用了 HID_HOST，没有 HFP、A2DP、LE_AUDIO。手环不是标准蓝牙音频设备，语音只能走私有链路；编码待查 | 运行观察（2026-10-30，`dumpsys bluetooth_manager`） |
| 语音唤起由谁接收（运动健康 App、小爱、系统服务）？ | `com.mi.health` 3.57.0 内置 `com.xiaomi.fitness.aivs`（小爱 AIVS SDK，`libaivs_jni.so`）以及 `libopus.so`、`libjni_byopus.so`、`libopustool.so`。推测手环录音以 Opus 编码发给运动健康，再由 AIVS 上云 | 代码线索（dex 字符串与 native 库名，未反编译） |
| 是否存在手环/手表录音并同步到手机的功能？ | 存在，见下方“穿戴端录音同步”。手环 9 是否具备该能力待确认 | 代码线索（JADX 反编译 `com.mi.health` 3.57.0） |
| 是否只能按键或唤醒词触发、有固定时长上限？ | 待查 | |
| 第三方 App 能否在不破坏官方连接的情况下发起或接收音频？ | 待查 | |
| 持续收音对手环续航的影响 | 待查 | |

在得到真机验证之前，手环接入不作为能力对外描述。

### 穿戴端录音同步（代码线索）

来源：JADX 反编译 `com.mi.health` 3.57.0，包 `com.xiaomi.fitness.devicesettings.bluttooth.audio`。

- **流程**：手机请求录音列表，穿戴端回复数据包类型 16，内容为 protobuf 编码的文件列表；手机逐个拉取文件，成功后通知穿戴端（`askDeviceReceiveFileSuccess`），再写入本地 Room 数据库 `AudioRecordDataBase`（`AudioRecordViewModel`）。
- **格式**：拉取到的文件用 `MediaExtractor` 解码，说明是标准容器；之后重新编码为 16 kHz 单声道 AAC（`AudioDecodeRunnable`），再提交到小米云端转写和总结（`AudioTransManager`、`AsrResult`、`MeetingBean`、`SummaryResult`）。
- **能力开关**（`FeatureItemModule.provideAudioRecordItem`，`RecordSyncUtils`）：
  - `deviceCapabilityV2 & 1024`：穿戴端支持录音，运动健康中显示“录音”入口。
  - `deviceCapabilityV2 & 524288`，且系统为 HyperOS 2 及以上：录音改为同步到系统录音机 `com.android.soundrecorder`（目录 `/recorder/sync/`），运动健康不再显示自己的录音入口。
  - 系统录音机需要 versionCode ≥ 602090。本机为 708099（7.8.9.9），满足条件（运行观察）。
- **对 Dayweave 的意义**：如果手环 9 具备 524288 能力，手环录音会落成系统录音机里的普通文件，Dayweave 可能只需读取文件即可，不需要实现私有协议。不过这种方式是“手动录一段再同步”，不是持续采集，录音时长上限和触发方式还要实测。
- 手机上的 `/sdcard/MIUI/sound_recorder`、`/sdcard/Recordings` 存在，`/sdcard/recorder` 不存在，说明当前没有发生过穿戴端同步（运行观察）。

## 小米 13 Ultra / HyperOS 3

| 问题 | 结论 | 证据等级 |
| --- | --- | --- |
| 前台麦克风服务在锁屏和长时间后台下是否会被系统杀掉？ | 待测 | |
| 需要哪些系统设置（自启动、省电策略白名单）？ | 待测 | |
| 口袋中手机麦克风的收音效果 | 待测 | |

## 电平偏低的排查（2026-10-30）

第 1 轮耳机测试的电平一直在 −60 到 −65 dBFS，看起来像没有采集到声音。用 adb 自动启动探针、手机麦克风采集、旁边的电脑朗读中英混合句子做对照：

| 音源 | 安静时 | 朗读时 | 系统静音 | 启动就绪 |
| --- | --- | --- | --- | --- |
| `MIC`（原始麦克风） | −50 到 −54 dBFS | −20 到 −25 dBFS | 否 | 297 ms |
| `VOICE_COMMUNICATION`（通话降噪） | −44 到 −61 dBFS | −37 到 −41 dBFS，句间被压到约 −70 dBFS | 否 | 615 ms |
| `MIC` + 蓝牙耳机 `bt_sco:REDMI Buds 8`（耳机放在电脑旁） | −47 到 −55 dBFS | −25 到 −35 dBFS | 否 | 403 ms |

结论（真机验证，手机麦克风）：**采集是真实有效的**。`VOICE_COMMUNICATION` 的降噪和自动增益让语音电平比原始麦克风低约 15 dB，这是第 1 轮电平偏低的主要原因。转写场景应优先使用 `MIC` 音源，或者至少比较两者的识别效果。蓝牙耳机加 `MIC` 音源同样能正常采集（真机验证）。用户确认降噪的表现符合预期（2026-10-30）。

探针在启动时会等待“系统未静音且读到非零数据”后才开始计时；不满足时每 3 秒重建一次录音，最多等 10 秒，用来应对“仅在使用中允许”授权刚生效时的延迟。

## 多蓝牙设备与地址隐藏（2026-10-30）

- 用户反馈：耳机和车载蓝牙同时连接时，系统默认用最后连接的设备作为输入。探针已改为手动选择输入设备。
- HyperOS 3 上，`AudioDeviceInfo.getAddress()` 对蓝牙设备返回 `00:00:00:00:00:00`（已授予 `BLUETOOTH_CONNECT`），不能靠地址区分多台蓝牙设备。探针改为同时比较类型、地址和设备名（运行观察）。耳机和车载同时在线时的切换行为待测。
