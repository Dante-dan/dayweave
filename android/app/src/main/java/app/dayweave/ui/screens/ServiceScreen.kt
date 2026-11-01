@file:OptIn(ExperimentalLayoutApi::class)

package app.dayweave.ui.screens

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dayweave.ai.digestModel
import app.dayweave.cloud.CloudAccount
import app.dayweave.cloud.CloudPricing
import app.dayweave.cloud.CloudStatus
import app.dayweave.cloud.Plan
import app.dayweave.core.AiConfig
import app.dayweave.core.AiProvider
import app.dayweave.core.ServiceMode
import app.dayweave.core.cloudAvailable
import app.dayweave.graph
import app.dayweave.transcribe.ChirpClient
import app.dayweave.ui.Actions
import app.dayweave.ui.Navigator
import app.dayweave.ui.components.ShimmerLines
import app.dayweave.ui.components.glass
import app.dayweave.ui.components.pressable
import app.dayweave.ui.theme.Glass
import app.dayweave.ui.theme.Ink
import app.dayweave.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import app.dayweave.core.tr

/** 服务与用量：在拾日云（按有效转录时长付费）和自带密钥（源码版）之间切换。 */
@Composable
fun ServiceScreen(nav: Navigator, actions: Actions) {
    val graph = LocalContext.current.graph
    val mode by graph.settings.mode.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundGlassButton(nav.back) { Icon(Icons.AutoMirrored.Outlined.ArrowBackIos, tr("返回", "Back"), modifier = Modifier.size(18.dp).padding(start = 4.dp)) }
        }
        Column(Modifier.padding(start = 4.dp)) {
            Text(tr("服务与用量", "Service & usage"), style = Type.Display)
            Text(
                if (cloudAvailable) tr("用拾日云省心，或者填自己的密钥", "Use Dayweave Cloud, or bring your own keys") else tr("源码版：转写和整理都使用你自己的密钥", "Source build: transcription and digests use your own keys"),
                style = Type.Caption, modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (cloudAvailable) ModeSwitch(mode, graph.settings::setMode)

        AnimatedContent(
            targetState = mode,
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
            label = "mode",
        ) { m ->
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (m == ServiceMode.CLOUD) CloudPanel() else OwnKeysPanel(actions)
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(32.dp))
    }
}

/** 两段式玻璃切换，高亮块回弹滑动。 */
@Composable
private fun ModeSwitch(mode: ServiceMode, onChange: (ServiceMode) -> Unit) {
    val options = listOf(ServiceMode.CLOUD to tr("拾日云", "Dayweave Cloud"), ServiceMode.OWN to tr("自带密钥", "Own keys"))
    BoxWithConstraints(Modifier.fillMaxWidth().glass(RoundedCornerShape(50), elevation = 4.dp).height(48.dp).padding(4.dp)) {
        val half = maxWidth / 2
        val x by animateDpAsState(if (mode == ServiceMode.CLOUD) 0.dp else half, spring(dampingRatio = 0.75f, stiffness = 420f), label = "x")
        Box(Modifier.offset(x = x).width(half).fillMaxHeight().clip(RoundedCornerShape(50)).background(Ink.Primary))
        Row(Modifier.fillMaxSize()) {
            options.forEach { (m, label) ->
                val color by animateColorAsState(if (m == mode) Ink.OnPrimary else Ink.Primary, label = "c")
                Box(Modifier.weight(1f).fillMaxHeight().pressable({ onChange(m) }, scaleTo = 0.95f), contentAlignment = Alignment.Center) {
                    Text(label, style = Type.Headline.copy(color = color))
                }
            }
        }
    }
}

@Composable
private fun CloudPanel() {
    val graph = LocalContext.current.graph
    val status by graph.cloud.status.collectAsStateWithLifecycle()
    val pricing by graph.cloud.pricing.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { graph.cloud.refresh() }

    val account = (status as? CloudStatus.Ready)?.account
    when (val s = status) {
        is CloudStatus.Ready -> BalanceCard(s.account)
        is CloudStatus.Failed -> Card {
            Text(s.message, style = Type.Headline)
            Pill(tr("重试", "Retry")) { graph.cloud.refresh() }
        }
        CloudStatus.NoLicense -> Unit
        else -> Card { ShimmerLines(3) }
    }
    PlansCard(account)
    LicenseCard(status)
    pricing?.let { PricingCard(it) }
    (status as? CloudStatus.Ready)?.account?.ledger?.takeIf { it.isNotEmpty() }?.let { ledger ->
        Card {
            Label(tr("最近的记录", "Recent activity"))
            ledger.take(12).forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (e.kind == "assist") tr("AI 整理、提醒与助手对话", "AI digests, reminders and chats")
                            else tr("转写 ${e.seconds} 秒人声", "Transcribed ${e.seconds}s of voice"),
                            style = Type.Body,
                        )
                        Text(stamp(e.at), style = Type.Micro)
                    }
                    Text(
                        (if (e.micros >= 0) "+" else "−") + usd(kotlin.math.abs(e.micros)),
                        style = Type.Caption.copy(color = if (e.micros >= 0) Color(0xFF2EB67D) else Ink.Primary, fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

@Composable
private fun BalanceCard(account: CloudAccount) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Ink.StoryBrush).padding(2.dp)
            .clip(RoundedCornerShape(26.dp)).background(Glass.Veil.copy(alpha = 0.9f)).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val plan = account.plan
        if (plan != null) {
            // 订阅：按时长展示，本周期结束时重置
            val total = account.grantedMicros / account.priceMicrosPerMinute.coerceAtLeast(1)
            Text(tr("拾日云 · ${plan.label}", "Dayweave Cloud · ${plan.label}"), style = Type.Micro.copy(color = Ink.Secondary))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(hours(account.minutesLeft), style = Type.Display)
                Text(tr(" / ${hours(total)}", " / ${hours(total)}"), style = Type.Caption, modifier = Modifier.padding(bottom = 6.dp))
            }
            account.expiresAt?.let {
                Text(tr("本月剩余人声时长 · ${day(it)} 续期重置", "Voice left this period · resets ${day(it)}"), style = Type.Caption.copy(color = Ink.Primary, fontWeight = FontWeight.Medium))
            }
        } else {
            Text(tr("授权码剩余额度", "License balance"), style = Type.Micro.copy(color = Ink.Secondary))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(usd(account.balanceMicros), style = Type.Display)
                if (account.grantedMicros > 0) Text(" / ${usd(account.grantedMicros)}", style = Type.Caption, modifier = Modifier.padding(bottom = 6.dp))
            }
            Text(tr("约可转录 ${account.minutesLeft} 分钟人声", "About ${account.minutesLeft} minutes of voice left"), style = Type.Caption.copy(color = Ink.Primary, fontWeight = FontWeight.Medium))
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(tr("本月有效转录", "Voice this month"), tr("${account.monthSeconds / 60} 分 ${account.monthSeconds % 60} 秒", "${account.monthSeconds / 60}m ${account.monthSeconds % 60}s"))
            Stat(tr("本月花费", "Spent this month"), usd(account.monthMicros))
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Glass.Lavender.copy(alpha = 0.45f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, style = Type.Micro.copy(color = Ink.Secondary))
        Text(value, style = Type.Headline)
    }
}

@Composable
private fun PricingCard(p: CloudPricing) {
    Card {
        Label(tr("怎么计费", "How pricing works"))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(usd(p.priceMicrosPerMinute, digits = 4), style = Type.Title)
            Text(tr(" / 分钟有效人声", " / minute of voice"), style = Type.Caption, modifier = Modifier.padding(bottom = 2.dp))
        }
        Text(
            tr("只按真正送去转写的人声时长计费。静音、环境音在手机上就被过滤掉；听音乐、播客和看视频的声音不录、不收费。", "You only pay for voice that's actually transcribed. Silence and room noise are filtered on the phone; music, podcasts and videos are never recorded or charged. ") +
                tr("订阅已包含每日整理、主动提醒和助手对话；授权码的这些 AI 功能按实际用量从余额里扣，通常远少于转写。", "Subscriptions include digests, reminders and assistant chats. With a license code they draw on the balance by actual use, usually far less than transcription."),
            style = Type.Caption,
        )
    }
}

/**
 * 订阅方案：价格以 Google Play 返回的本地价格为准，商店还没上架时显示美元标价。
 * 选中方案只靠颜色区分，不做描边到实心的变化。
 */
@Composable
private fun PlansCard(account: CloudAccount?) {
    val context = LocalContext.current
    val graph = context.graph
    val offers by graph.store.offers.collectAsStateWithLifecycle()
    val busy by graph.store.busy.collectAsStateWithLifecycle()
    val message by graph.store.message.collectAsStateWithLifecycle()
    var yearly by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { graph.store.load() }
    val current = account?.plan
    Card {
        Label(tr("订阅拾日云", "Subscribe to Dayweave Cloud"))
        Text(
            tr("每月发放固定的人声时长，未用完的不结转；年付送 2 个月。可随时在 Google Play 取消。", "A monthly allowance of voice that doesn't roll over; yearly billing gets 2 months free. Cancel any time in Google Play."),
            style = Type.Caption,
        )
        PeriodSwitch(yearly) { yearly = it }
        Plan.entries.filter { !yearly || it.yearlyUsd != null }.forEach { plan ->
            val offer = offers.firstOrNull { it.plan == plan && it.yearly == yearly }
            val selected = plan == current
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(if (selected) Ink.Accent.copy(alpha = 0.12f) else Glass.Veil.copy(alpha = 0.55f))
                    .pressable({ if (offer != null && !selected) context.activity()?.let { graph.store.purchase(it, offer) } }, scaleTo = 0.98f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(plan.label, style = Type.Headline.copy(color = if (selected) Ink.Accent else Ink.Primary))
                    Text(tr("每月 ${plan.hours} 小时人声 · ${plan.perDay}", "${plan.hours} h of voice a month · ${plan.perDay}"), style = Type.Micro)
                }
                val price = offer?.price ?: if (yearly) plan.yearlyUsd.orEmpty() else plan.monthlyUsd
                Text(
                    if (selected) tr("当前方案", "Current") else if (yearly) tr("$price / 年", "$price / yr") else tr("$price / 月", "$price / mo"),
                    style = Type.Caption.copy(color = if (selected) Ink.Accent else Ink.Primary, fontWeight = FontWeight.SemiBold),
                )
            }
        }
        if (offers.isEmpty()) {
            Text(tr("订阅将随 Google Play 上架开放；现在可以先用授权码。", "Subscriptions open with the Google Play release. A license code works today."), style = Type.Micro)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(if (busy) tr("核验中", "Verifying") else tr("恢复购买", "Restore purchases"), enabled = !busy) { graph.store.restore() }
            Spacer(Modifier.width(10.dp))
            message?.let { Text(it, style = Type.Caption, modifier = Modifier.weight(1f)) }
        }
    }
}

/** 月付 / 年付：与服务方式切换同一套回弹滑块。 */
@Composable
private fun PeriodSwitch(yearly: Boolean, onChange: (Boolean) -> Unit) {
    val options = listOf(false to tr("月付", "Monthly"), true to tr("年付 · 送 2 个月", "Yearly · 2 months free"))
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Glass.Veil.copy(alpha = 0.55f)).height(40.dp).padding(3.dp)) {
        val half = maxWidth / 2
        val x by animateDpAsState(if (yearly) half else 0.dp, spring(dampingRatio = 0.75f, stiffness = 420f), label = "x")
        Box(Modifier.offset(x = x).width(half).fillMaxHeight().clip(RoundedCornerShape(50)).background(Ink.Primary))
        Row(Modifier.fillMaxSize()) {
            options.forEach { (value, label) ->
                val color by animateColorAsState(if (value == yearly) Ink.OnPrimary else Ink.Primary, label = "c")
                Box(Modifier.weight(1f).fillMaxHeight().pressable({ onChange(value) }, scaleTo = 0.95f), contentAlignment = Alignment.Center) {
                    Text(label, style = Type.Caption.copy(color = color, fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

private tailrec fun android.content.Context.activity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.activity()
    else -> null
}

private fun hours(minutes: Long): String = if (minutes >= 60) "%.1f h".format(minutes / 60.0) else "$minutes min"

private fun day(ms: Long): String = DateTimeFormatter.ofPattern(tr("M月d日", "MMM d")).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** 授权码：没有账户，授权码就是使用权。每个码有固定额度，用完换一个新码。 */
@Composable
private fun LicenseCard(status: CloudStatus) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val current = graph.settings.licenseCode
    var editing by remember(current) { mutableStateOf(current.isNullOrBlank()) }
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Card {
        Label(tr("授权码", "License code"))
        if (!editing && current != null && current.startsWith("DS-")) {
            // 订阅凭证由恢复购买自动管理，这里只提供改用授权码
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("正在使用订阅", "Using your subscription"), style = Type.Body, modifier = Modifier.weight(1f))
                Pill(tr("改用授权码", "Use a code")) { editing = true; message = null }
            }
        } else if (!editing && current != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 只显示首尾，避免截屏时泄露完整授权码
                Text(mask(current), style = Type.Headline, modifier = Modifier.weight(1f))
                Pill(tr("更换", "Change")) { editing = true; message = null }
            }
            if ((status as? CloudStatus.Ready)?.account?.balanceMicros?.let { it <= 0 } == true) {
                Text(tr("这个授权码的额度已用完，换一个新的授权码即可继续。", "This code is used up. Enter a new one to continue."), style = Type.Caption.copy(color = Ink.Danger))
            }
        } else {
            Text(tr("购买授权码后填在这里。每个授权码有固定额度，用完换新的。", "Enter the license code you bought. Each code has a fixed allowance; use a new one when it runs out."), style = Type.Caption)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(code, { code = it.uppercase(); message = null }, "DW-XXXXX-XXXXX-XXXXX-XXXXX", Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Pill(if (busy) tr("验证中", "Checking") else tr("使用", "Use"), icon = Icons.Outlined.Key, dark = true, enabled = code.isNotBlank() && !busy) {
                    busy = true
                    scope.launch {
                        val error = withContext(Dispatchers.IO) { graph.cloud.activate(code) }
                        busy = false
                        message = error
                        if (error == null) {
                            code = ""
                            editing = false
                            graph.cloud.refresh()
                        }
                    }
                }
            }
        }
        message?.let { Text(it, style = Type.Caption.copy(color = Ink.Danger)) }
    }
}

private fun mask(code: String): String {
    val c = code.trim()
    return if (c.length <= 10) "••••" else "${c.take(8)} •••• ${c.takeLast(4)}"
}

@Composable
private fun OwnKeysPanel(actions: Actions) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var saVersion by remember { mutableStateOf(0) }
    val project = remember(saVersion) { graph.auth.projectId.takeIf { graph.auth.available } }
    val email = remember(saVersion) { graph.auth.email }
    var saMessage by remember { mutableStateOf<String?>(null) }

    Card {
        Header(Icons.Outlined.GraphicEq, tr("转写 · Google Speech-to-Text", "Transcription · Google Speech-to-Text"), tr("Chirp 3，中英混说，自动区分说话人", "Chirp 3, mixed Chinese and English, tells speakers apart"))
        if (project != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF2EB67D), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(project, style = Type.Headline)
                    email?.let { Text(it, style = Type.Micro, maxLines = 1) }
                }
            }
        } else {
            Text(
                tr("在 Google Cloud 控制台创建服务账号，授予“Cloud Speech 客户端”角色（用 Vertex AI 整理时再加“Vertex AI 用户”），下载 JSON 密钥后导入。", "In the Google Cloud console, create a service account with the Cloud Speech Client role (add Vertex AI User for Vertex digests), download a JSON key and import it. ") +
                    tr("费用由你的 Google Cloud 账号直接支付。", "Your Google Cloud account is billed directly."),
                style = Type.Caption,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (project == null) tr("导入服务账号 JSON", "Import service account JSON") else tr("更换", "Replace"), dark = project == null) {
                actions.importServiceAccount { error ->
                    saMessage = error ?: tr("已导入", "Imported")
                    saVersion++
                }
            }
            if (project != null) {
                Pill(tr("测试", "Test")) {
                    saMessage = tr("正在测试…", "Testing…")
                    scope.launch {
                        saMessage = withContext(Dispatchers.IO) {
                            runCatching { ChirpClient(graph.auth).recognize(ShortArray(16_000), 0, 16_000) }
                                .fold({ tr("连接正常", "Connected") }, { tr("失败：${it.message?.take(120)}", "Failed: ${it.message?.take(120)}") })
                        }
                    }
                }
                Pill(tr("移除", "Remove")) { graph.auth.clear(); saVersion++; saMessage = null }
            }
        }
        saMessage?.let { Text(it, style = Type.Caption) }
        Text(tr("密钥只保存在这台手机的 App 私有目录里，不会上传到拾日云。", "Keys stay in the app's private storage on this phone and are never sent to Dayweave Cloud."), style = Type.Micro)
    }

    AiCard()
}

@Composable
private fun AiCard() {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val saved by graph.settings.ai.collectAsStateWithLifecycle()
    var draft by remember(saved) { mutableStateOf(saved) }
    var message by remember { mutableStateOf<String?>(null) }
    val dirty = draft != saved

    Card {
        Header(Icons.Outlined.AutoAwesome, tr("整理 · AI 模型", "Digest · AI model"), tr("每天的总结、决定、要跟进的事", "Daily summary, decisions and follow-ups"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AiProvider.entries.forEach { p ->
                Pill(p.label, dark = draft.provider == p) { draft = draft.copy(provider = p, model = ""); message = null }
            }
        }
        Text(
            when (draft.provider) {
                AiProvider.GEMINI -> tr("在 Google AI Studio 创建 API 密钥。", "Create an API key in Google AI Studio.")
                AiProvider.OPENAI -> tr("支持 OpenAI、DeepSeek、通义、Kimi、OpenRouter 等兼容 Chat Completions 的服务，需要支持 JSON Schema 输出。", "Works with OpenAI, DeepSeek, Qwen, Kimi, OpenRouter and other Chat Completions services that support JSON Schema output.")
                AiProvider.VERTEX -> tr("使用上面导入的服务账号，需要在项目里开通 Vertex AI。", "Uses the service account above. Vertex AI must be enabled in the project.")
            },
            style = Type.Caption,
        )
        if (draft.provider != AiProvider.VERTEX) {
            Field(draft.apiKey, { draft = draft.copy(apiKey = it) }, tr("API 密钥", "API key"), secret = true)
        }
        if (draft.provider == AiProvider.OPENAI) {
            Field(draft.baseUrl, { draft = draft.copy(baseUrl = it) }, tr("接口地址，默认 https://api.openai.com/v1", "Base URL, default https://api.openai.com/v1"))
        }
        Field(draft.model, { draft = draft.copy(model = it) }, tr("模型，默认 ${draft.defaultModel}", "Model, default ${draft.defaultModel}"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(tr("保存", "Save"), dark = true, enabled = dirty) {
                graph.settings.setAi(draft)
                message = tr("已保存", "Saved")
            }
            Pill(tr("测试", "Test")) {
                message = tr("正在测试…", "Testing…")
                val config = draft
                scope.launch { message = withContext(Dispatchers.IO) { testAi(config, graph) } }
            }
        }
        message?.let { Text(it, style = Type.Caption) }
    }
}

private fun testAi(config: AiConfig, graph: app.dayweave.AppGraph): String {
    val model = digestModel(config, graph.auth)
    model.problem?.let { return it }
    return runCatching {
        model.generateJson(
            "只输出 JSON。", "回复 ok 为 true。",
            Json.parseToJsonElement("""{"type":"OBJECT","required":["ok"],"properties":{"ok":{"type":"BOOLEAN"}}}"""),
        )
    }.fold({ tr("连接正常 · ${model.name}", "Connected · ${model.name}") }, { tr("失败：${it.message?.take(160)}", "Failed: ${it.message?.take(160)}") })
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), elevation = 6.dp).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun Label(text: String) = Text(text, style = Type.Micro.copy(color = Ink.Secondary))

@Composable
private fun Header(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Ink.StoryBrush), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = Type.Headline)
            Text(subtitle, style = Type.Caption)
        }
    }
}

@Composable
private fun Pill(text: String, icon: ImageVector? = null, dark: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (dark && enabled) Ink.Primary else Glass.Veil.copy(alpha = if (enabled) 0.85f else 0.5f))
            .border(1.dp, Glass.Veil, RoundedCornerShape(50))
            .pressable(onClick, enabled = enabled, scaleTo = 0.94f)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val color = if (dark && enabled) Ink.OnPrimary else if (enabled) Ink.Primary else Ink.Tertiary
        icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(15.dp)) }
        Text(text, style = Type.Caption.copy(color = color, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier.fillMaxWidth(), secret: Boolean = false) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(Glass.Veil.copy(alpha = 0.8f))
            .border(1.dp, Glass.Edge, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, style = Type.Body.copy(color = Ink.Tertiary), maxLines = 1)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = Type.Body.copy(color = Ink.Primary),
            cursorBrush = SolidColor(Ink.Primary),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun usd(micros: Long, digits: Int = 2): String = "\$" + "%.${digits}f".format(micros / 1_000_000.0)


private fun stamp(ms: Long) = app.dayweave.ui.components.dateTime(ms)
