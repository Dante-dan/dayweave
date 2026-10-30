package app.dayweave.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 用户选中的麦克风输入。蓝牙地址在 HyperOS 上会被隐藏，所以同时记设备名。 */
data class InputSelection(val type: Int, val address: String, val name: String)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _input = MutableStateFlow(readInput())
    val input: StateFlow<InputSelection?> = _input

    private val _capturePlayback = MutableStateFlow(prefs.getBoolean("capture_playback", true))
    /** 是否同时采集手机正在播放的声音（例如耳机里的会议、视频）。 */
    val capturePlayback: StateFlow<Boolean> = _capturePlayback

    private fun readInput(): InputSelection? {
        val name = prefs.getString("input_name", null) ?: return null
        return InputSelection(prefs.getInt("input_type", 0), prefs.getString("input_address", "").orEmpty(), name)
    }

    fun setInput(selection: InputSelection) {
        prefs.edit()
            .putInt("input_type", selection.type)
            .putString("input_address", selection.address)
            .putString("input_name", selection.name)
            .apply()
        _input.value = selection
    }

    fun setCapturePlayback(enabled: Boolean) {
        prefs.edit().putBoolean("capture_playback", enabled).apply()
        _capturePlayback.value = enabled
    }
}
