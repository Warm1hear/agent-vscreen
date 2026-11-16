package com.kilon.agentvscreen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 1.4: the app's pages are drawn with miuix. These are the few building blocks the pages call, under the names the
 * pages already used (Text, Button, OutlinedButton, TextButton, Switch, AlertDialog), so every page of the app moves to
 * one look at once instead of page by page. Files that must keep Material 3 (the floating window's own pages) import
 * it explicitly, and an explicit import wins over these.
 */

/** The page's content layer, for glass that blurs what is behind it (API 33+; a translucent fill below that). */
val LocalBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
fun Text(
    text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null, fontFamily: FontFamily? = null, lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip, textAlign: TextAlign? = null, softWrap: Boolean = true,
    style: TextStyle? = null,
) = top.yukonga.miuix.kmp.basic.Text(
    text, modifier, color = color, fontSize = fontSize, fontWeight = fontWeight, fontFamily = fontFamily, lineHeight = lineHeight,
    maxLines = maxLines, overflow = overflow, textAlign = textAlign, softWrap = softWrap,
    style = style ?: MiuixTheme.textStyles.main,
)

@Composable
fun Icon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    top.yukonga.miuix.kmp.basic.Icon(imageVector, contentDescription, modifier, tint)

/** The main action: filled violet. [color] for a coloured action (a banner's own button). */
@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color? = null, content: @Composable RowScope.() -> Unit) =
    top.yukonga.miuix.kmp.basic.Button(onClick, modifier, enabled,
        colors = if (color != null) ButtonDefaults.buttonColorsPrimary(color = color) else ButtonDefaults.buttonColorsPrimary(),
        content = content)

/** A second action: a quiet tinted pill. */
@Composable
fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    top.yukonga.miuix.kmp.basic.Button(onClick, modifier, enabled,
        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.secondaryContainer, contentColor = MiuixTheme.colorScheme.primary),
        content = content)

/** A text action (dialogs, small links): no fill, violet words; the same size as the other buttons so a row of them lines up. */
@Composable
fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    top.yukonga.miuix.kmp.basic.Button(onClick, modifier, enabled,
        colors = ButtonDefaults.buttonColors(color = Color.Transparent, disabledColor = Color.Transparent, contentColor = MiuixTheme.colorScheme.primary),
        content = content)

@Composable
fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) =
    top.yukonga.miuix.kmp.basic.Switch(checked, onCheckedChange, modifier, enabled = enabled)

/**
 * The app's dialog: a pane of violet-tinted frosted glass. Over a page that shares its [LocalBackdrop] the page behind
 * is blurred (API 33+); elsewhere, or on older Android, it is a translucent fill. Same slots as Material's AlertDialog.
 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null,
) {
    val backdrop = LocalBackdrop.current
    val c = MiuixTheme.colorScheme
    val tint = c.surfaceContainer
    val body: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().then(
                if (backdrop != null) Modifier.drawBackdrop(backdrop, shape = { RoundedCornerShape(28.dp) },
                    effects = { blur(28.dp.toPx()) }, onDrawSurface = { drawRect(tint.copy(alpha = 0.72f)) })
                else Modifier
            ).padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides c.onSurface) {
                title?.let { t -> CompositionLocalProvider(LocalContentColor provides c.onSurface) { t() } }
                text?.let { t -> Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { t() } }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                    dismissButton?.invoke(); confirmButton()
                }
            }
        }
    }
    if (backdrop != null) top.yukonga.miuix.kmp.overlay.OverlayDialog(
        show = true, modifier = modifier, backgroundColor = if (Glass.blurOk) Color.Transparent else tint.copy(alpha = 0.94f),
        onDismissRequest = onDismissRequest, insideMargin = DpSize(0.dp, 0.dp), cornerRadius = 28.dp, content = body,
    ) else top.yukonga.miuix.kmp.window.WindowDialog(
        show = true, modifier = modifier, backgroundColor = tint.copy(alpha = 0.94f),
        onDismissRequest = onDismissRequest, insideMargin = DpSize(0.dp, 0.dp), cornerRadius = 28.dp, content = body,
    )
}

/** A dialog title in the app's style. */
@Composable
fun DialogTitle(t: String) = Text(t, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)

/** The top bar as glass: the page scrolls under it, blurred (API 33+), or a near-opaque fill below that. */
fun Modifier.drawBackdropBar(backdrop: LayerBackdrop, surface: Color): Modifier =
    if (Glass.blurOk) this.drawBackdrop(backdrop, shape = { androidx.compose.ui.graphics.RectangleShape },
        effects = { blur(24.dp.toPx()) }, onDrawSurface = { drawRect(surface.copy(alpha = 0.62f)) })
    else this.then(Modifier.background(surface.copy(alpha = 0.94f)))
