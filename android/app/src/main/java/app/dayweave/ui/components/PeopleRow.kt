package app.dayweave.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dayweave.core.Person
import app.dayweave.ui.motion.bloomTap
import app.dayweave.ui.theme.Ink
import app.dayweave.ui.theme.Type
import app.dayweave.core.tr

/** 像快拍一样的一排人：没起名字的人带渐变光环，提示“点我标注”。 */
@Composable
fun PeopleRow(people: List<Person>, onClick: (Person) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(people, key = { it.id }) { p ->
            Column(
                Modifier.width(64.dp).bloomTap({ onClick(p) }, scaleTo = 0.9f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(p, 60.dp, ring = p.isOwner || !p.named)
                Text(
                    if (p.named || p.isOwner) p.name else tr("这是谁？", "Who's this?"),
                    style = Type.Micro.copy(color = if (p.named || p.isOwner) Ink.Primary else Ink.Pink),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }
}
