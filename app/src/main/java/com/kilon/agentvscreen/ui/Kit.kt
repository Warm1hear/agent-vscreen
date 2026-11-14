package com.kilon.agentvscreen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.tr

/*
 * 1.3 beta3: the app's one design system. Everything is glass on a soft violet backdrop: cards in the app, the
 * question card on the floating window and the full screen, the folded tab. One accent family (the brand violet),
 * with the state colours (standby blue, takeover green, stopped red, asking orchid) only as small signals.
 * Text is short where it is shown; the long explanations live behind a "?" ([Help]).
 */

object Glass {
    /** Over the live background screen (floating window, full screen): dark, translucent, a light edge on top. */
    val darkFill = Brush.verticalGradient(listOf(Color(0xC2221D3D), Color(0xDE120F22)))
    val darkEdge = Brush.verticalGradient(listOf(Color(0x73FFFFFF), Color(0x1AFFFFFF)))
    /** A button on dark glass that is not the main one. */
    val darkChip = Color(0x29FFFFFF)
    /** The main action: brand violet into the asking orchid. */
    val accent = Brush.horizontalGradient(listOf(Color(0xFF7B6CFF), Color(0xFFB57BFF)))
}

/** Dark glass for things that float over the background screen. */
fun Modifier.glassDark(shape: Shape): Modifier = this.clip(shape).background(Glass.darkFill).border(1.dp, Glass.darkEdge, shape)

/** Light glass (dark glass in dark mode) for the app's own cards. */
@Composable
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), lift: Boolean = true): Modifier {
    val dark = isSystemInDarkTheme()
    val fill = if (dark) Brush.verticalGradient(listOf(Color(0xB3232740), Color(0x99181B2C)))
        else Brush.verticalGradient(listOf(Color(0xD9FFFFFF), Color(0xB8FFFFFF)))
    val edge = if (dark) Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x0DFFFFFF)))
        else Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0x66D9D4F5)))
    return this
        .then(if (lift) Modifier.shadow(if (dark) 0.dp else 10.dp, shape, ambientColor = Color(0x225B4BDB), spotColor = Color(0x225B4BDB)) else Modifier)
        .clip(shape).background(fill).border(1.dp, edge, shape)
}

/** The app's backdrop: soft violet and cyan light behind the glass, so the glass reads as glass. */
@Composable
fun Modifier.backdrop(): Modifier {
    val dark = isSystemInDarkTheme()
    val base = MaterialTheme.colorScheme.background
    return this.background(base).drawBehind {
        val a = if (dark) 0.20f else 0.22f
        drawCircle(Brush.radialGradient(listOf(Color(0xFF7B6CFF).copy(alpha = a), Color.Transparent), center = Offset(size.width * 0.1f, size.height * 0.04f), radius = size.width * 0.9f),
            radius = size.width * 0.9f, center = Offset(size.width * 0.1f, size.height * 0.04f))
        drawCircle(Brush.radialGradient(listOf(Color(0xFF22D3EE).copy(alpha = a * 0.7f), Color.Transparent), center = Offset(size.width * 0.95f, size.height * 0.18f), radius = size.width * 0.75f),
            radius = size.width * 0.75f, center = Offset(size.width * 0.95f, size.height * 0.18f))
        drawCircle(Brush.radialGradient(listOf(Color(0xFFD67BFF).copy(alpha = a * 0.6f), Color.Transparent), center = Offset(size.width * 0.3f, size.height * 0.75f), radius = size.width * 0.8f),
            radius = size.width * 0.8f, center = Offset(size.width * 0.3f, size.height * 0.75f))
    }
}

/** A small "?" that opens the explanation: the long words live here, not under every setting. */
@Composable
fun Help(title: String, body: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.size(32.dp).clip(CircleShape).clickable { open = true }, contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Outlined.HelpOutline, tr("说明", "About this"), tint = tint, modifier = Modifier.size(18.dp))
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = { Text(body, fontSize = 14.sp, lineHeight = 21.sp) },
        confirmButton = { TextButton(onClick = { open = false }) { Text(tr("知道了", "Got it")) } },
    )
}

/** One glass card with an optional small header (and its "?"). */
@Composable
fun Section(title: String? = null, help: String? = null, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title != null) Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            trailing?.invoke()
            if (help != null) Help(title, help)
        }
        content()
    }
}

/** A row that opens something: title, a short value, a chevron. */
@Composable
fun NavRow(title: String, value: String? = null, icon: ImageVector? = null, valueColor: Color? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { IconBubble(icon, MaterialTheme.colorScheme.primary, 32); Spacer(Modifier.width(12.dp)) }
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (value != null) Text(value, fontSize = 13.sp, color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A switch with a short line under it (a few words) and, when there is more to say, a "?". */
@Composable
fun SwitchRow(title: String, sub: String? = null, checked: Boolean, help: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (help != null) Help(title, help)
            }
            if (sub != null) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A short grey line. One line where it can be. */
@Composable
fun Note(t: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(t, fontSize = 12.sp, lineHeight = 17.sp, color = color, modifier = Modifier.fillMaxWidth())

@Composable
fun IconBubble(icon: ImageVector, color: Color, size: Int = 36) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.32f).dp)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size((size * 0.55f).dp))
    }
}

/** A square-ish tile on the home grid: an icon, a name, and the one fact worth seeing without opening it. */
@Composable
fun Tile(icon: ImageVector, title: String, value: String, color: Color, modifier: Modifier = Modifier, alert: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier.glass(RoundedCornerShape(22.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon, color)
            Spacer(Modifier.weight(1f))
            if (alert) Box(Modifier.size(9.dp).clip(CircleShape).background(Palette.stopped))
        }
        Column {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, fontSize = 12.sp, color = if (alert) Palette.stopped else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A second-level page: back, title, its "?" when the page needs words, then glass sections. */
@Composable
fun PageHeader(title: String, help: String? = null, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("返回", "Back")) }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (help != null) Help(title, help)
    }
}

/** A pill button for dark glass: the main one in the accent gradient, the rest as quiet glass chips. */
@Composable
fun GlassPill(label: String, primary: Boolean, k: Float = 1f, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(50)).then(if (primary) Modifier.background(Glass.accent) else Modifier.background(Glass.darkChip).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(50)))
            .clickable(onClick = onClick).padding(horizontal = (14 * k).dp, vertical = (7 * k).dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = (12.5f * k).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}
