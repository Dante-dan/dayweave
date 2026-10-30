package app.dayweave

import android.app.Application
import android.content.Context
import app.dayweave.ai.DigestEngine
import app.dayweave.ai.GeminiClient
import app.dayweave.core.ArchiveStore
import app.dayweave.core.Settings
import app.dayweave.transcribe.ChirpClient
import app.dayweave.transcribe.GoogleAuth
import app.dayweave.transcribe.TranscriptionPipeline
import app.dayweave.voice.VoiceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** 手动组装的依赖。个人规模的 demo 不需要 DI 框架。 */
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(context)
    val archive = ArchiveStore(context.filesDir)
    val voice = VoiceId(context, context.filesDir)
    val auth = GoogleAuth(File(context.filesDir, "google-sa.json"))
    val pipeline = TranscriptionPipeline(
        archive = archive,
        chirp = ChirpClient(auth),
        voice = voice,
        audioDir = File(context.cacheDir, "segments").apply { mkdirs() },
        scope = scope,
    )
    val digest = DigestEngine(
        archive = archive,
        gemini = GeminiClient(auth),
        people = { voice.people.value },
        root = context.filesDir,
        scope = scope,
    )
}

class DayweaveApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as DayweaveApp).graph
