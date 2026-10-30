package app.dayweave.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.widget.Toast
import app.dayweave.MainActivity
import app.dayweave.graph
import java.time.Instant
import java.time.ZoneId

private const val EVENT_LEAD_MS = 15 * 60_000L

/** 执行一条建议：交给系统闹钟或日历。日程会打开日历的新建页，由用户确认保存。 */
fun perform(context: Context, s: Suggestion, action: String): Boolean {
    val intent = when (action) {
        "alarm" -> {
            val at = s.at ?: return false
            val ring = if (s.kind == "event") at - EVENT_LEAD_MS else at
            val time = Instant.ofEpochMilli(ring).atZone(ZoneId.systemDefault())
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, time.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, s.title)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        "event" -> Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, s.at ?: return false)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, s.endAt ?: (s.at + 60 * 60_000L))
            .putExtra(CalendarContract.Events.TITLE, s.title)
            .putExtra(CalendarContract.Events.DESCRIPTION, listOf(s.why, s.quote).filter { it.isNotBlank() }.joinToString("\n") + "\n— 来自拾日")
            .apply { s.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) } }
        else -> return false
    }
    return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

/** 通知按钮的落点：在自己的界面里转交给闹钟或日历（系统不允许从后台直接打开别的 App）。 */
class SuggestionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val agent = graph.agent
        val s = intent.getStringExtra("id")?.let(agent::get)
        when (val action = intent.getStringExtra("action")) {
            null -> Unit
            "dismiss" -> s?.let { agent.setStatus(it.id, "dismissed") }
            "open" -> startActivity(Intent(this, MainActivity::class.java).putExtra("tab", "digest").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            else -> if (s != null) {
                if (perform(this, s, action)) {
                    agent.setStatus(s.id, "done")
                    if (action == "alarm") Toast.makeText(this, "已设好闹钟", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "没有找到可用的闹钟或日历应用", Toast.LENGTH_SHORT).show()
                }
            }
        }
        finish()
    }
}
