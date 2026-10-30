package app.dayweave

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.dayweave.capture.CaptureService
import app.dayweave.ui.Actions
import app.dayweave.ui.DayweaveRoot
import app.dayweave.ui.DeepLink
import kotlinx.coroutines.flow.MutableStateFlow
import app.dayweave.ui.theme.DayweaveTheme

class MainActivity : ComponentActivity(), Actions {

    private var afterPermission: (() -> Unit)? = null
    private val deepLink = MutableStateFlow<DeepLink?>(null)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val next = afterPermission
        afterPermission = null
        if (granted[Manifest.permission.RECORD_AUDIO] != false && hasMic()) next?.invoke()
    }

    /** 采集播放声音需要系统的录屏授权；拒绝时只记录麦克风。 */
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        CaptureService.start(this, result.data.takeIf { result.resultCode == Activity.RESULT_OK })
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        readDeepLink(intent)
    }

    /**
     * 调试与演示用的跳转（HyperOS 默认禁止 adb 模拟点击）：
     * adb shell am start -n app.dayweave/.MainActivity --es tab timeline|capture|digest [--es conversation <id>] [--es day 2026-10-30] [--ez enroll true]
     */
    private fun readDeepLink(intent: android.content.Intent?) {
        intent ?: return
        val link = DeepLink(
            tab = intent.getStringExtra("tab"),
            conversation = intent.getStringExtra("conversation"),
            day = intent.getStringExtra("day"),
            enroll = intent.getBooleanExtra("enroll", false),
        )
        if (link != DeepLink()) deepLink.value = link
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun requestMicPermission(then: () -> Unit) {
        afterPermission = then
        permissions.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    override fun toggleCapture() {
        if (CaptureService.state.value.running) {
            CaptureService.stop(this)
            return
        }
        requestMicPermission {
            if (graph.settings.capturePlayback.value) {
                projection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            } else {
                CaptureService.start(this, null)
            }
        }
    }
}
