# audio-probe：持续采集探针

一次性验证工具，不是 Dayweave 正式 App 的代码基础。它只回答开发顺序第一步的问题：

> 选定的输入设备，能否在锁屏、手机放口袋的状态下长时间持续收音？路由何时丢失、能否恢复？耗电如何？

它只采集和测量，不做转写，也不上传任何数据。

## 它做什么

- 前台服务（`microphone` 类型）+ partial wake lock，以 16 kHz 单声道采集。
- **手动选择输入设备**：列出所有在线的蓝牙通话设备（耳机、车载可以同时出现）和手机麦克风，由用户指定。蓝牙设备用地址区分；断线恢复时只连回选中的那台，不会跳到别的蓝牙设备。
- **音源可选**：`通话降噪`（`VOICE_COMMUNICATION`，带系统降噪和回声消除）或 `原始麦克风`（`MIC`）。
- **启动等待**：“仅在使用中允许”的授权刚生效时，系统可能先给静音数据。探针会等到“系统未静音且读到非零数据”才开始计时；不满足时每 3 秒重建一次录音，最多等 10 秒，并记录等了多久。
- 每 10 秒向 `metrics.csv` 写一行：音源、实际路由输入、通话设备、平均和峰值电平、全零帧比例、**系统静音比例**、其他录音 App 数量、实际读取和理论应读取的采样数、电量、瞬时电流、屏幕是否点亮。
- `events.txt` 记录设备增删、路由变化、静音状态变化和读取错误；同样的内容也会输出到 logcat，标签为 `DayweaveProbe`，每 0.5 秒还会输出一次实时电平。
- 启动后先保存 30 秒 WAV 样本，之后每 15 分钟保存 20 秒，也可以手动采样，用于云端转写评估。

## 调试启动（已授权时）

```bash
adb shell am start -n app.dayweave.probe/.MainActivity --es autostart builtin --es source MIC
adb shell am start -n app.dayweave.probe/.MainActivity --es autostart bluetooth   # 第一台在线蓝牙设备
adb logcat -s DayweaveProbe
adb shell am force-stop app.dayweave.probe                                       # 停止
```

## 构建与安装

```bash
cd spikes/audio-probe
./gradlew :app:installDebug
```

## 测试流程

每一轮都记下：耳机型号和固件、手机系统版本、是否开启省电模式、测试场景。

1. **基础连通**：连上 Redmi Buds 8，在输入设备列表里选中它，开始。确认“实际输入”显示 `bt_sco:` 或 `ble_headset:`，而不是 `builtin_mic`。
2. **锁屏持续**：锁屏，手机放口袋，正常说话和交谈至少 1 小时。
3. **对方声音**：与另一位知情的人面对面交谈，试听样本，判断能否听清对方说话。
4. **中断恢复**：中途把耳机放回充电盒再拿出；接一个电话；播放音乐。观察 `events.txt` 中路由是否丢失和恢复。
5. **对照组**：选“手机麦克风”在口袋里跑同样时长，对比电平、清晰度和耗电。
6. **全天**：在条件允许时跑 8 小时以上，统计耗电和缺口。

## 取回日志

```bash
adb pull /sdcard/Android/data/app.dayweave.probe/files/sessions ./captures/
```

`/captures/` 和 `*.wav` 已被 `.gitignore` 排除，录音样本不会进入仓库。测试结束后请删除手机和电脑上的样本。

## 判读要点

| 现象 | 含义 |
| --- | --- |
| `routed_input` 长时间为 `bt_sco` / `ble_headset` | 耳机持续收音可行 |
| `routed_input` 回落到 `builtin_mic` | 系统改了路由，需要查看 `events.txt` 中的原因 |
| `samples_read` 明显低于 `samples_expected` | 采集被挂起或节流（锁屏、省电策略） |
| `zero_frame_ratio` 接近 1 | 输入被静音或链路断开，但录音会话本身还在 |
| `current_ua` 差值（蓝牙对比手机麦克风） | 维持 SCO 链路的额外耗电 |

结论需要区分“代码线索、运行观察、真机验证”三类，写入 `docs/research/`。

## 已知限制

- SCO/通话链路通常为 8–16 kHz 窄带或宽带语音，音质会低于手机麦克风，最终影响需要看 ASR 的识别效果。
- 长时间占用通话链路时，耳机播放音乐会切换为通话音质，这是持续录音方案本身的副作用。
- 小米手环暂不在这个探针的范围内；它需要先通过 APK 和协议调查确认接入路径。
