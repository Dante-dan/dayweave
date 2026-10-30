package app.dayweave.capture

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.dayweave.MainActivity

/**
 * 控制中心里的“拾日”快捷开关：随时开始、暂停或继续，不用打开 App。
 * 记录中轻触是暂停 / 继续；结束记录在通知里。
 */
class CaptureTile : TileService() {

    override fun onStartListening() {
        val s = CaptureService.state.value
        qsTile?.apply {
            state = if (s.listening) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = when {
                !s.running -> "未记录"
                s.paused -> "已暂停"
                else -> "聆听中"
            }
            updateTile()
        }
    }

    override fun onClick() {
        val s = CaptureService.state.value
        when {
            s.running && s.paused -> CaptureService.resume(this)
            s.running -> CaptureService.pause(this)
            else -> {
                // 后台不能直接启动麦克风服务，借一个透明的界面启动，然后马上关掉。
                val intent = Intent(this, StartCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            }
        }
        onStartListening()
    }
}

/** 透明跳板：有录音权限就直接开始记录并退出；没有就打开 App 去授权。 */
class StartCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            CaptureService.start(this)
        } else {
            startActivity(Intent(this, MainActivity::class.java).putExtra("tab", "capture").putExtra("start", true))
        }
        finish()
    }
}
