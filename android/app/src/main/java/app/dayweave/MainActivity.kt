package app.dayweave

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.dayweave.capture.CaptureService
import app.dayweave.capture.MediaListener
import app.dayweave.ui.Actions
import app.dayweave.ui.DayweaveRoot
import app.dayweave.ui.DeepLink
import app.dayweave.ui.theme.DayweaveTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity(), Actions {

    private var afterPermission: (() -> Unit)? = null
    private var afterImport: ((String?) -> Unit)? = null
    private val deepLink = MutableStateFlow<DeepLink?>(null)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val next = afterPermission
        afterPermission = null
        if (granted[Manifest.permission.RECORD_AUDIO] != false && hasMic()) next?.invoke()
    }

    /** 导入服务账号 JSON：只读用户选中的这一个文件。 */
    private val pickJson = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val done = afterImport ?: return@registerForActivityResult
        afterImport = null
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    graph.auth.import(text)
                }.exceptionOrNull()
            }
            done(error?.message?.let { if (error is IllegalArgumentException) it else "读取失败：$it" })
        }
    }

    /** 授权通话录音文件夹：系统文件选择器，只读这一个文件夹。 */
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            graph.callRecordings.enable(uri)
            graph.callRecordings.scan()
        }
    }

    override fun onResume() {
        super.onResume()
        graph.callRecordings.scan()
    }

    override fun pickCallRecordings() {
        pickFolder.launch(
            android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", app.dayweave.capture.CALL_REC_DOCUMENT),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { DayweaveTheme { DayweaveRoot(this, deepLink) } }
        readDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readDeepLink(intent)
    }

    /**
     * 通知、快捷开关和调试用的跳转（HyperOS 默认禁止 adb 模拟点击）：
     * adb shell am start -n app.dayweave/.MainActivity --es tab timeline|capture|digest [--es conversation <id>] [--es day 2026-10-30] [--ez enroll true] [--ez service true]
     */
    private fun readDeepLink(intent: Intent?) {
        intent ?: return
        val link = DeepLink(
            tab = intent.getStringExtra("tab"),
            conversation = intent.getStringExtra("conversation"),
            day = intent.getStringExtra("day"),
            enroll = intent.getBooleanExtra("enroll", false),
            service = intent.getBooleanExtra("service", false),
        )
        if (link != DeepLink()) deepLink.value = link
        if (BuildConfig.DEBUG) intent.getStringExtra("importCall")?.let { graph.callRecordings.importForTest(java.io.File(cacheDir, it)) }
        if (intent.getBooleanExtra("start", false) && !CaptureService.state.value.running) toggleCapture()
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun requestMicPermission(then: () -> Unit) {
        afterPermission = then
        permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT))
    }

    override fun toggleCapture() {
        if (CaptureService.state.value.running) {
            CaptureService.stop(this)
            return
        }
        requestMicPermission {
            CaptureService.start(this)
            if (graph.settings.autoBackground.value) goBackgroundWhenReady()
        }
    }

    /** 麦克风真正开始工作后回到桌面，之后在通知栏或控制中心里暂停、结束。 */
    private fun goBackgroundWhenReady() {
        lifecycleScope.launch {
            withTimeoutOrNull(6_000) { CaptureService.state.first { it.micReady || (!it.running && it.notice != null) } }
            delay(900) // 让光环转起来，用户看见“已经在听了”
            if (CaptureService.state.value.running) moveTaskToBack(true)
        }
    }

    override fun importServiceAccount(done: (String?) -> Unit) {
        afterImport = done
        pickJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    }

    /** “识别正在播放的内容”需要通知使用权，只用来读正在播放的媒体信息。 */
    override fun openMediaAccess() {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, MediaListener::class.java).flattenToString())
        runCatching { startActivity(detail) }.onFailure { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }
}
