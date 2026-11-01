package app.dayweave

import android.app.Application
import android.content.Context
import app.dayweave.ai.Agent
import app.dayweave.ai.CloudDigest
import app.dayweave.ai.DigestEngine
import app.dayweave.ai.DigestModel
import app.dayweave.ai.digestModel
import app.dayweave.capture.CallRecordings
import app.dayweave.capture.SpeechDetector
import app.dayweave.cloud.CloudApi
import app.dayweave.cloud.PlayStore
import app.dayweave.core.ArchiveStore
import app.dayweave.core.ServiceMode
import app.dayweave.core.Settings
import app.dayweave.transcribe.ChirpClient
import app.dayweave.transcribe.GoogleAuth
import app.dayweave.transcribe.SpeechEngine
import app.dayweave.transcribe.TranscriptionPipeline
import app.dayweave.voice.VoiceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** 手动组装的依赖。个人规模的 App 不需要 DI 框架。 */
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(context)
    val archive = ArchiveStore(context.filesDir)
    val voice = VoiceId(context, context.filesDir)
    val auth = GoogleAuth(File(context.filesDir, "google-sa.json"))
    val cloud = CloudApi(settings, scope)
    val store = PlayStore(context, cloud, scope)
    private val chirp = ChirpClient(auth)

    /** 按当前的服务方式选择转写：拾日云，或用户自己的 Google Cloud。 */
    val speech: SpeechEngine get() = if (settings.mode.value == ServiceMode.CLOUD) cloud else chirp

    val digestModel: DigestModel
        get() = if (settings.mode.value == ServiceMode.CLOUD) CloudDigest(cloud) else digestModel(settings.ai.value, auth)

    val agent = Agent(
        context = context,
        model = { digestModel },
        people = { voice.people.value },
        root = context.filesDir,
        scope = scope,
    )

    val pipeline = TranscriptionPipeline(
        archive = archive,
        onDone = agent::consider,
        engine = { speech },
        detector = SpeechDetector(context),
        voice = voice,
        audioDir = File(context.cacheDir, "segments").apply { mkdirs() },
        scope = scope,
    )
    val callRecordings = CallRecordings(
        context = context,
        submit = pipeline::submit,
        audioDir = File(context.cacheDir, "segments"),
        scope = scope,
    )

    /** 账本：记账助手听到的消费 + 手记。 */
    val ledger = app.dayweave.ai.Ledger(context.filesDir)

    val digest = DigestEngine(
        archive = archive,
        model = { digestModel },
        people = { voice.people.value },
        assistants = { settings.assistants.value },
        onDigest = { date, d -> if (app.dayweave.ai.Assistant.BOOKKEEPING in settings.assistants.value) ledger.absorb(date, d.expenses) },
        root = context.filesDir,
        scope = scope,
    )

    val chat = app.dayweave.ai.ChatEngine(
        root = context.filesDir,
        model = { digestModel },
        digests = { digest.digests.value },
        ledger = ledger,
        scope = scope,
    )
}

/** 合并两个说话人：声纹并过去，档案里的发言也改过去。 */
fun AppGraph.mergePeople(from: String, into: String) {
    voice.merge(from, into)
    archive.reassignSpeaker(from, into)
}

/** 声纹整理：一组组合并到保留的人，档案一次改完。 */
fun AppGraph.mergeGroups(groups: List<app.dayweave.voice.VoiceGroup>) {
    val map = mutableMapOf<String, String>()
    groups.forEach { g -> g.members.drop(1).forEach { m -> voice.merge(m, g.keep); map[m] = g.keep } }
    archive.reassignSpeakers(map)
}

/** 忘掉零散声纹，他们说过的话改成“未识别”。 */
fun AppGraph.forgetVoices(ids: List<String>) {
    voice.forget(ids)
    archive.reassignSpeakers(ids.associateWith { app.dayweave.core.UNKNOWN_ID })
}

class DayweaveApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        app.dayweave.core.Lang.english = app.dayweave.core.Lang.resolve(graph.settings.language.value)
        graph.voice.relabel()
        // 订阅每月续费后由 Google Play 核验新的周期：启动时悄悄把有效订阅再交给拾日云一次。
        if (graph.settings.licenseCode?.startsWith("DS-") == true) graph.store.restore(quiet = true)
        app.dayweave.capture.showStandby(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as DayweaveApp).graph
