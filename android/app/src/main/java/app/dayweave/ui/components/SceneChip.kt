package app.dayweave.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dayweave.core.Scene
import app.dayweave.core.SceneKind
import app.dayweave.ui.theme.Glass
import app.dayweave.ui.theme.Ink
import app.dayweave.ui.theme.Type

val SceneKind.icon: ImageVector
    get() = when (this) {
        SceneKind.MUSIC -> Icons.Rounded.MusicNote
        SceneKind.PODCAST -> Icons.Rounded.Podcasts
        SceneKind.VIDEO -> Icons.Rounded.SmartDisplay
        SceneKind.CALL -> Icons.Rounded.Call
        SceneKind.MEDIA -> Icons.Rounded.GraphicEq
    }

/** 每种场景一个柔和的粉彩底色。 */
val SceneKind.tint: Color
    get() = when (this) {
        SceneKind.MUSIC -> Glass.Pink
        SceneKind.PODCAST -> Glass.Butter
        SceneKind.VIDEO -> Glass.Sky
        SceneKind.CALL -> Glass.Mint
        SceneKind.MEDIA -> Glass.Lavender
    }

/** 像 ins 帖子里的“正在听”标签：[♪ 听音乐 · 汽水音乐]。 */
@Composable
fun SceneChip(scene: Scene, modifier: Modifier = Modifier, prefix: String? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(scene.kind.tint.copy(alpha = 0.85f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(scene.kind.icon, null, tint = Ink.Primary, modifier = Modifier.size(13.dp))
        Text(
            listOfNotNull(prefix ?: scene.kind.label, scene.app.takeIf { prefix == null }, scene.title).joinToString(" · "),
            style = Type.Micro.copy(color = Ink.Primary, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
