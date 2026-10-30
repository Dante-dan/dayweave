@file:OptIn(ExperimentalLayoutApi::class)

package app.dayweave.ui.screens

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
import androidx.compose.material.icons.outlined.Redeem
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
            RoundGlassButton(nav.back) { Icon(Icons.AutoMirrored.Outlined.ArrowBackIos, "返回", modifier = Modifier.size(18.dp).padding(start = 4.dp)) }
        }
        Column(Modifier.padding(start = 4.dp)) {
            Text("服务与用量", style = Type.Display)
            Text(
                if (cloudAvailable) "用拾日云省心，或者填自己的密钥" else "源码版：转写和整理都使用你自己的密钥",
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
    val options = listOf(ServiceMode.CLOUD to "拾日云", ServiceMode.OWN to "自带密钥")
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

    when (val s = status) {
        is CloudStatus.Ready -> BalanceCard(s.account)
        is CloudStatus.Failed -> Card {
            Text("暂时连不上拾日云", style = Type.Headline)
            Text(s.message, style = Type.Caption, maxLines = 3)
            Pill("重试") { graph.cloud.refresh() }
        }
        else -> Card { ShimmerLines(3) }
    }
    pricing?.let { PricingCard(it) }
    RedeemCard()
    (status as? CloudStatus.Ready)?.account?.ledger?.takeIf { it.isNotEmpty() }?.let { ledger ->
        Card {
            Label("最近的记录")
            ledger.take(12).forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (e.kind) {
                                "transcribe" -> "转写 ${e.seconds} 秒人声"
                                "redeem" -> "兑换 ${e.note}"
                                "trial" -> "新用户体验额度"
                                else -> e.note.ifBlank { e.kind }
                            },
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
        Text("余额", style = Type.Micro.copy(color = Ink.Secondary))
        Text(usd(account.balanceMicros), style = Type.Display)
        Text("约可转录 ${account.minutesLeft} 分钟人声", style = Type.Caption.copy(color = Ink.Primary, fontWeight = FontWeight.Medium))
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("本月有效转录", "${account.monthSeconds / 60} 分 ${account.monthSeconds % 60} 秒")
            Stat("本月花费", usd(account.monthMicros))
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
        Label("怎么计费")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(usd(p.priceMicrosPerMinute, digits = 3), style = Type.Title)
            Text(" / 分钟有效人声", style = Type.Caption, modifier = Modifier.padding(bottom = 2.dp))
        }
        Text(
            "只按真正送去转写的人声时长计费。静音、环境音在手机上就被过滤掉；听音乐、播客和看视频的声音不录、不收费。" +
                "每日整理不另外收费。" + p.note,
            style = Type.Caption,
        )
    }
}

@Composable
private fun PriceLine(icon: ImageVector, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(Glass.Veil.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Ink.Primary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = Type.Body.copy(fontWeight = FontWeight.Medium))
            Text(detail, style = Type.Micro)
        }
    }
}

@Composable
private fun RedeemCard() {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Card {
        Label("充值")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(code, { code = it; message = null }, "输入兑换码", Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Pill(if (busy) "兑换中" else "兑换", icon = Icons.Outlined.Redeem, enabled = code.isNotBlank() && !busy) {
                busy = true
                scope.launch {
                    val error = withContext(Dispatchers.IO) { graph.cloud.redeem(code) }
                    busy = false
                    message = error ?: "已到账"
                    if (error == null) code = ""
                }
            }
        }
        message?.let { Text(it, style = Type.Caption.copy(color = if (it == "已到账") Color(0xFF2EB67D) else Ink.Danger)) }
    }
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
        Header(Icons.Outlined.GraphicEq, "转写 · Google Speech-to-Text", "Chirp 3，中英混说，自动区分说话人")
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
                "在 Google Cloud 控制台创建服务账号，授予“Cloud Speech 客户端”角色（用 Vertex AI 整理时再加“Vertex AI 用户”），下载 JSON 密钥后导入。" +
                style = Type.Caption,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (project == null) "导入服务账号 JSON" else "更换", dark = project == null) {
                actions.importServiceAccount { error ->
                    saMessage = error ?: "已导入"
                    saVersion++
                }
            }
            if (project != null) {
                Pill("测试") {
                    saMessage = "正在测试…"
                    scope.launch {
                        saMessage = withContext(Dispatchers.IO) {
                            runCatching { ChirpClient(graph.auth).recognize(ShortArray(16_000), 0, 16_000) }
                                .fold({ "连接正常" }, { "失败：${it.message?.take(120)}" })
                        }
                    }
                }
                Pill("移除") { graph.auth.clear(); saVersion++; saMessage = null }
            }
        }
        saMessage?.let { Text(it, style = Type.Caption) }
        Text("密钥只保存在这台手机的 App 私有目录里，不会上传到拾日云。", style = Type.Micro)
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
        Header(Icons.Outlined.AutoAwesome, "整理 · AI 模型", "每天的总结、决定、要跟进的事")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AiProvider.entries.forEach { p ->
                Pill(p.label, dark = draft.provider == p) { draft = draft.copy(provider = p, model = ""); message = null }
            }
        }
        Text(
            when (draft.provider) {
                AiProvider.GEMINI -> "在 Google AI Studio 创建 API 密钥。"
                AiProvider.OPENAI -> "支持 OpenAI、DeepSeek、通义、Kimi、OpenRouter 等兼容 Chat Completions 的服务，需要支持 JSON Schema 输出。"
                AiProvider.VERTEX -> "使用上面导入的服务账号，需要在项目里开通 Vertex AI。"
            },
            style = Type.Caption,
        )
        if (draft.provider != AiProvider.VERTEX) {
            Field(draft.apiKey, { draft = draft.copy(apiKey = it) }, "API 密钥", secret = true)
        }
        if (draft.provider == AiProvider.OPENAI) {
            Field(draft.baseUrl, { draft = draft.copy(baseUrl = it) }, "接口地址，默认 https://api.openai.com/v1")
        }
        Field(draft.model, { draft = draft.copy(model = it) }, "模型，默认 ${draft.defaultModel}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("保存", dark = true, enabled = dirty) {
                graph.settings.setAi(draft)
                message = "已保存"
            }
            Pill("测试") {
                message = "正在测试…"
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
    }.fold({ "连接正常 · ${model.name}" }, { "失败：${it.message?.take(160)}" })
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

private fun Double.clean() = if (this % 1.0 == 0.0) toInt().toString() else toString()

private val stampFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault())
private fun stamp(ms: Long) = stampFormat.format(Instant.ofEpochMilli(ms))
