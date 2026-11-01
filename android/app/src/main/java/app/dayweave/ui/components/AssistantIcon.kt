package app.dayweave.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.ui.graphics.vector.ImageVector
import app.dayweave.ai.Assistant

fun assistantIcon(a: Assistant): ImageVector = when (a) {
    Assistant.PARENTING -> Icons.Outlined.ChildCare
    Assistant.HEALTH -> Icons.Outlined.FavoriteBorder
    Assistant.RELATIONSHIP -> Icons.Outlined.People
    Assistant.FINANCE -> Icons.Outlined.Savings
    Assistant.LIFE -> Icons.Outlined.Lightbulb
    Assistant.GROWTH -> Icons.Outlined.AutoAwesome
    Assistant.BOOKKEEPING -> Icons.Outlined.ReceiptLong
    Assistant.WORK -> Icons.Outlined.WorkOutline
    Assistant.LEARNING -> Icons.Outlined.MenuBook
    Assistant.OCCASIONS -> Icons.Outlined.Cake
    Assistant.SHOPPING -> Icons.Outlined.ShoppingBag
}
