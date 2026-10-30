@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.dayweave.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dayweave.capture.CaptureService
import app.dayweave.ui.components.Aurora
import app.dayweave.ui.components.LocalHaze
import app.dayweave.ui.components.frosted
import app.dayweave.ui.components.pressable
import androidx.compose.ui.geometry.Offset
import app.dayweave.ui.motion.LitIcon
import app.dayweave.ui.motion.blossomIcon
import app.dayweave.ui.screens.CaptureScreen
import app.dayweave.ui.screens.ConversationScreen
import app.dayweave.ui.screens.DayScreen
import app.dayweave.ui.screens.DigestScreen
import app.dayweave.ui.screens.EnrollScreen
import app.dayweave.ui.screens.PersonScreen
import app.dayweave.ui.screens.ServiceScreen
import app.dayweave.ui.screens.TimelineScreen
import app.dayweave.ui.theme.Glass
import app.dayweave.ui.theme.Ink
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import java.time.LocalDate

data class DeepLink(
    val tab: String? = null,
    val conversation: String? = null,
    val day: String? = null,
    val enroll: Boolean = false,
    val service: Boolean = false,
    /** 每次跳转都不同，同样的链接点第二次也会生效（StateFlow 会丢弃相等的值）。 */
    val nonce: Long = 0,
)

/** 由 Activity 提供、需要系统授权弹窗或系统页面的动作。 */
interface Actions {
    fun toggleCapture()
    fun requestMicPermission(then: () -> Unit)
    /** 选择服务账号 JSON 并导入；回调参数为错误说明，成功时为 null。 */
    fun importServiceAccount(done: (String?) -> Unit)
    fun openMediaAccess()
    /** 选择系统通话录音文件夹，开启自动导入。 */
    fun pickCallRecordings()
}

private sealed interface Route {
    data object Tabs : Route
    data class Conversation(val id: String) : Route
    data class Day(val date: LocalDate) : Route
    data object Enroll : Route
    data object Service : Route
    data class Person(val id: String) : Route
}

/** 底栏：选中与否用同一个实心图形，只靠颜色和光影区分（和 iOS 的标签栏一致）。 */
enum class Tab(val icon: ImageVector, val label: String) {
    DIGEST(blossomIcon(filled = true), "拾日"),
    TIMELINE(Icons.Rounded.ViewAgenda, "时间线"),
    CAPTURE(Icons.Rounded.GraphicEq, "采集"),
}

/** 页面跳转用的回调集合。 */
class Navigator(
    val openConversation: (String) -> Unit,
    val openDay: (LocalDate) -> Unit,
    val openEnroll: () -> Unit,
    val openService: () -> Unit,
    val openPerson: (String) -> Unit,
    val openTab: (Tab) -> Unit,
    val back: () -> Unit,
)

val LocalShared = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimated = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** 悬浮导航栏的高度，列表底部需要留出这段空间。 */
val TabBarClearance = 112.dp

/** 列表卡片与详情页之间的共享边界过渡。 */
fun Modifier.sharedSegment(key: String): Modifier = composed {
    val shared = LocalShared.current
    val animated = LocalAnimated.current
    if (shared == null || animated == null) return@composed this
    with(shared) {
        this@composed.sharedBounds(
            rememberSharedContentState("shared-$key"),
            animatedVisibilityScope = animated,
            boundsTransform = { _, _ -> spring(dampingRatio = 0.86f, stiffness = 320f) },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
    }
}

@Composable
fun DayweaveRoot(actions: Actions, deepLinks: kotlinx.coroutines.flow.StateFlow<DeepLink?>) {
    var stack by rememberSaveable(stateSaver = RouteSaver) { mutableStateOf(listOf<Route>(Route.Tabs)) }
    var tab by rememberSaveable { mutableStateOf(Tab.DIGEST) }
    val route = stack.last()
    val nav = Navigator(
        openConversation = { stack = stack + Route.Conversation(it) },
        openDay = { stack = stack + Route.Day(it) },
        openEnroll = { stack = stack + Route.Enroll },
        openService = { stack = stack + Route.Service },
        openPerson = { stack = stack + Route.Person(it) },
        openTab = { tab = it },
        back = { if (stack.size > 1) stack = stack.dropLast(1) },
    )
    BackHandler(enabled = stack.size > 1) { nav.back() }
    val link by deepLinks.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(link) {
        val l = link ?: return@LaunchedEffect
        l.tab?.let { name -> Tab.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { tab = it } }
        stack = listOfNotNull(
            Route.Tabs,
            l.day?.let { Route.Day(LocalDate.parse(it)) },
            l.conversation?.let { Route.Conversation(it) },
            if (l.enroll) Route.Enroll else null,
            if (l.service) Route.Service else null,
        )
    }
    val haze = remember { HazeState() }

    CompositionLocalProvider(LocalHaze provides haze) {
        Box(Modifier.fillMaxSize()) {
            Aurora(Modifier.haze(haze)) {
                SharedTransitionLayout(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = route,
                        transitionSpec = {
                            when {
                                targetState is Route.Enroll ->
                                    (slideInVertically(tween(460, easing = FastOutSlowInEasing)) { it / 5 } + fadeIn(tween(320))) togetherWith fadeOut(tween(220))
                                initialState is Route.Enroll ->
                                    fadeIn(tween(300)) togetherWith (slideOutVertically(tween(380)) { it / 5 } + fadeOut(tween(260)))
                                targetState is Route.Service || targetState is Route.Person ->
                                    (slideInHorizontally(tween(420, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(320))) togetherWith fadeOut(tween(200))
                                initialState is Route.Service || initialState is Route.Person ->
                                    fadeIn(tween(300)) togetherWith (slideOutHorizontally(tween(360)) { it / 4 } + fadeOut(tween(240)))
                                targetState is Route.Day && initialState is Route.Tabs ->
                                    (slideInHorizontally(tween(420, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(320))) togetherWith
                                        (fadeOut(tween(200)) + scaleOut(tween(300), targetScale = 0.96f))
                                initialState is Route.Day && targetState is Route.Tabs ->
                                    (fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.96f)) togetherWith
                                        (slideOutHorizontally(tween(360)) { it / 4 } + fadeOut(tween(240)))
                                // 对话详情靠共享边界展开，这里只做淡入淡出
                                else -> fadeIn(tween(320, delayMillis = 60)) togetherWith fadeOut(tween(220))
                            }
                        },
                        label = "route",
                    ) { r ->
                        CompositionLocalProvider(LocalShared provides this@SharedTransitionLayout, LocalAnimated provides this) {
                            when (r) {
                                Route.Tabs -> TabContent(tab, actions, nav)
                                is Route.Conversation -> ConversationScreen(r.id, nav)
                                is Route.Day -> DayScreen(r.date, nav)
                                Route.Enroll -> EnrollScreen(nav, actions)
                                Route.Service -> ServiceScreen(nav, actions)
                                is Route.Person -> PersonScreen(r.id, nav)
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = route is Route.Tabs,
                enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 300f)) { it * 2 } + fadeIn(),
                exit = slideOutVertically(tween(260)) { it * 2 } + fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                TabBar(tab) { tab = it }
            }
        }
    }
}

@Composable
private fun TabContent(tab: Tab, actions: Actions, nav: Navigator) {
    AnimatedContent(
        targetState = tab,
        transitionSpec = {
            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (slideInHorizontally(tween(380, easing = FastOutSlowInEasing)) { it / 10 * dir } + fadeIn(tween(300, delayMillis = 40))) togetherWith
                (slideOutHorizontally(tween(300)) { -it / 10 * dir } + fadeOut(tween(160)))
        },
        label = "tab",
    ) { t ->
        when (t) {
            Tab.DIGEST -> DigestScreen(nav)
            Tab.TIMELINE -> TimelineScreen(nav)
            Tab.CAPTURE -> CaptureScreen(nav, actions)
        }
    }
}

/** 悬浮的玻璃胶囊导航：选中项下面有一块更亮的玻璃，随选择滑动。 */
@Composable
private fun TabBar(tab: Tab, onTab: (Tab) -> Unit) {
    val recording by CaptureService.state.collectAsStateWithLifecycle()
    val itemWidth = 96.dp
    // 液态的高亮块：位置走弹簧，宽度随速度拉长（|v| 越大越长），停下时收回原宽，像一滴水滑过去。
    val density = androidx.compose.ui.platform.LocalDensity.current
    val itemPx = with(density) { itemWidth.toPx() }
    val x = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(itemPx * tab.ordinal) }
    androidx.compose.runtime.LaunchedEffect(tab) {
        x.animateTo(itemPx * tab.ordinal, spring(dampingRatio = 0.74f, stiffness = 340f))
    }
    val stretch = (kotlin.math.abs(x.velocity) / 9_000f).coerceAtMost(0.45f) * itemPx
    val indicator = with(density) { (x.value - stretch / 2).toDp() }
    val indicatorWidth = with(density) { (itemPx + stretch).toDp() }
    Box(
        Modifier
            .navigationBarsPadding()
            .padding(bottom = 14.dp)
            .frosted(RoundedCornerShape(36.dp))
            .height(64.dp)
            .width(itemWidth * Tab.entries.size + 12.dp)
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .offset(x = indicator)
                .width(indicatorWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(30.dp))
                .background(Glass.Veil.copy(alpha = 0.85f)),
        )
        Row {
            Tab.entries.forEach { t ->
                val selected = t == tab
                val tint by animateColorAsState(if (selected) Ink.Accent else Ink.Secondary, label = "tint")
                Column(
                    Modifier.width(itemWidth).fillMaxHeight().pressable({ onTab(t) }, scaleTo = 0.88f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Box {
                        AnimatedContent(
                            targetState = selected,
                            transitionSpec = { scaleIn(spring(Spring.DampingRatioMediumBouncy), initialScale = 0.7f) + fadeIn() togetherWith fadeOut(tween(90)) },
                            label = "icon",
                        ) { s ->
                            // 选中时：强调色 + 顶光与投影，拾日的花心点一粒暖金；未选中：同一图形的灰色。
                            if (s) LitIcon(t.icon, t.label, color = Ink.Accent, heart = if (t == Tab.DIGEST) Offset(0.5f, 0.51f) else null)
                            else Icon(t.icon, t.label, tint = tint, modifier = Modifier.size(24.dp))
                        }
                        if (t == Tab.CAPTURE && recording.running) {
                            Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp).size(8.dp).clip(CircleShape).background(Ink.Live))
                        }
                    }
                    Text(t.label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = tint)
                }
            }
        }
    }
}

private val RouteSaver = androidx.compose.runtime.saveable.listSaver<List<Route>, String>(
    save = { routes ->
        routes.map {
            when (it) {
                Route.Tabs -> "tabs"
                Route.Enroll -> "enroll"
                Route.Service -> "service"
                is Route.Conversation -> "c:${it.id}"
                is Route.Day -> "d:${it.date}"
                is Route.Person -> "p:${it.id}"
            }
        }
    },
    restore = { saved ->
        saved.map {
            when {
                it == "enroll" -> Route.Enroll
                it == "service" -> Route.Service
                it.startsWith("c:") -> Route.Conversation(it.removePrefix("c:"))
                it.startsWith("d:") -> Route.Day(LocalDate.parse(it.removePrefix("d:")))
                it.startsWith("p:") -> Route.Person(it.removePrefix("p:"))
                else -> Route.Tabs
            }
        }
    },
)
