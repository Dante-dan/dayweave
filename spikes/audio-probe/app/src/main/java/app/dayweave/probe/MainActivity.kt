package app.dayweave.probe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { ProbeScreen(::listInputs, ::start, ::send) }
            }
        }
        autostart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        autostart(intent)
    }

    /**
     * 调试入口，权限已授予时可用 adb 直接启动：
     * adb shell am start -n app.dayweave.probe/.MainActivity --es autostart builtin|bluetooth [--es device Buds] [--es source MIC]
     */
    private fun autostart(intent: Intent?) {
        val kind = intent?.getStringExtra("autostart") ?: return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val name = intent.getStringExtra("device").orEmpty()
        val inputs = listInputs()
        Log.i("DayweaveProbe", "autostart candidates: ${inputs.joinToString { "${it.label}@${it.address}" }}")
        val choice = inputs.firstOrNull {
            (if (kind == "bluetooth") it.isBluetooth else !it.isBluetooth) && it.name.contains(name, ignoreCase = true)
        } ?: return
        val source = intent.getStringExtra("source")?.let(CaptureSource::valueOf) ?: CaptureSource.VOICE_COMMUNICATION
        start(choice, source)
    }

    /** 可选输入：每台在线的蓝牙通话设备各一项（耳机、车载可同时出现），外加手机麦克风。 */
    private fun listInputs(): List<InputChoice> {
        val audio = getSystemService(AudioManager::class.java)
        val bluetooth = audio.availableCommunicationDevices
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            .map { InputChoice(it.type, it.address, it.productName.toString()) }
        val builtin = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?.let { InputChoice(it.type, it.address, "手机麦克风") }
        return (bluetooth + listOfNotNull(builtin)).distinct()
    }

    private fun start(choice: InputChoice, source: CaptureSource) {
        val intent = Intent(this, ProbeService::class.java)
            .setAction(ProbeService.ACTION_START)
            .putExtra(ProbeService.EXTRA_DEVICE_TYPE, choice.type)
            .putExtra(ProbeService.EXTRA_DEVICE_ADDRESS, choice.address)
            .putExtra(ProbeService.EXTRA_DEVICE_NAME, choice.name)
            .putExtra(ProbeService.EXTRA_SOURCE, source.name)
        startForegroundService(intent)
    }

    private fun send(action: String) {
        startService(Intent(this, ProbeService::class.java).setAction(action))
    }
}

@Composable
private fun ProbeScreen(
    listInputs: () -> List<InputChoice>,
    start: (InputChoice, CaptureSource) -> Unit,
    send: (String) -> Unit,
) {
    val state by ProbeService.state.collectAsStateWithLifecycle()
    var inputs by remember { mutableStateOf(emptyList<InputChoice>()) }
    var selected by remember { mutableStateOf<InputChoice?>(null) }
    var source by remember { mutableStateOf(CaptureSource.VOICE_COMMUNICATION) }
    val refresh = {
        inputs = listInputs()
        if (selected !in inputs) selected = inputs.firstOrNull()
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.BLUETOOTH_CONNECT] == true) refresh()
        val choice = selected
        if (granted[Manifest.permission.RECORD_AUDIO] == true && choice != null) start(choice, source)
    }
    LaunchedEffect(Unit) { runCatching { refresh() } }

    Column(
        Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Dayweave 采集探针", style = MaterialTheme.typography.headlineSmall)
        Text("验证选定输入能否在锁屏下持续收音。仅在参与者知情的场景使用。")

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("输入设备", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(enabled = !state.running, onClick = { runCatching { refresh() } }) { Text("刷新") }
        }
        if (inputs.isEmpty()) Text("没有可用输入。首次使用请先点“开始”授予蓝牙权限，再刷新。")
        inputs.forEach { input ->
            Row(
                Modifier.fillMaxWidth().selectable(
                    selected = selected == input,
                    enabled = !state.running,
                    onClick = { selected = input },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == input, enabled = !state.running, onClick = { selected = input })
                Text(if (input.isBluetooth) "${input.name}（${typeName(input.type)}）" else input.name)
            }
        }

        Text("音源", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptureSource.entries.forEach {
                FilterChip(
                    selected = source == it,
                    enabled = !state.running,
                    onClick = { source = it },
                    label = { Text(if (it == CaptureSource.MIC) "原始麦克风" else "通话降噪") },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.running) {
                Button(onClick = {
                    permissions.launch(
                        arrayOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ),
                    )
                }) { Text("开始") }
            } else {
                Button(onClick = { send(ProbeService.ACTION_STOP) }) { Text("停止") }
                OutlinedButton(
                    enabled = state.ready && state.sampling == null,
                    onClick = { send(ProbeService.ACTION_SAMPLE) },
                ) { Text("采样 30 秒") }
            }
        }

        val status = when {
            !state.running -> "未运行"
            !state.ready -> "等待麦克风就绪…"
            else -> "收音中 ${state.elapsedSec}s"
        }
        Text("状态：$status")
        state.choice?.let { Text("选中输入：${it.label}") }
        Text("实际输入：${state.routedInput}")
        Text("通话设备：${state.commDevice}")
        Text("实时电平：" + (state.liveDbfs?.let { "%.1f dBFS".format(it) } ?: "-"))
        Text("10 秒均值：" + (state.meanDbfs?.let { "%.1f dBFS".format(it) } ?: "-"))
        state.silenced?.let { Text(if (it) "⚠️ 系统正在静音本 App 的录音" else "系统未静音") }
        state.sampling?.let { Text("正在采样：$it") }
        state.sessionDir?.let { Text("日志目录：$it", style = MaterialTheme.typography.bodySmall) }
        Text("最近事件：${state.lastEvent}", style = MaterialTheme.typography.bodySmall)
    }
}
