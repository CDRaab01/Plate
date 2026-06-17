package com.plate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.plate.ui.theme.PlateTheme

/**
 * The PULSE component kit, adapted to Plate. Flat panels (hairline stroke + tone, no shadows),
 * monospace data readouts, uppercase instrument captions, and a tiny sparkline — all driven by the
 * tokens in [PlateTheme.pulse] / [PlateTheme.dataType]. Mirrors Spotter's instrument-panel look.
 */

/** A flat, hairline-stroked surface — the base container for everything. Optional channel tint. */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    tint: Color? = null,
    content: @Composable () -> Unit,
) {
    val pulse = PlateTheme.pulse
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(tint ?: pulse.panel)
            .border(1.dp, pulse.hairline, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) { content() }
}

/** A monospace numeric readout. Defaults to the small data scale; pass a [style] for a hero figure. */
@Composable
fun DataText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = PlateTheme.dataType.dataSmall,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(text = text, modifier = modifier, style = style, color = color)
}

/** An uppercase, wide-tracked caption — the instrument label voice. */
@Composable
fun Caption(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
        fontWeight = FontWeight.Medium,
        color = color,
    )
}

/** Section header: a channel-colored tick followed by an uppercase label. */
@Composable
fun SectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    channel: Color = PlateTheme.pulse.carbs,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(channel),
        )
        Spacer(Modifier.width(8.dp))
        Caption(label, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A stat tile: caption + big mono value (+ optional unit), tinted by its channel. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    channel: Color = PlateTheme.pulse.carbs,
) {
    PanelCard(modifier = modifier) {
        Column {
            SectionHeader(label, channel = channel)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                DataText(value, style = PlateTheme.dataType.dataMedium, color = channel)
                if (unit != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        unit,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * A minimal sparkline / bar series. When [asBars] is true each value is a vertical bar (good for a
 * Mon–Sun weekly readout); otherwise the values are joined as a line. Scaled to the series max.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier.fillMaxWidth().height(48.dp),
    channel: Color = PlateTheme.pulse.carbs,
    asBars: Boolean = true,
) {
    val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    Canvas(modifier = modifier) {
        if (values.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        if (asBars) {
            val slot = w / values.size
            val barW = slot * 0.55f
            values.forEachIndexed { i, v ->
                val barH = (v / max) * h
                val left = i * slot + (slot - barW) / 2f
                drawRoundRect(
                    color = channel,
                    topLeft = Offset(left, h - barH),
                    size = androidx.compose.ui.geometry.Size(barW, barH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f, barW / 2f),
                )
            }
        } else {
            val step = if (values.size > 1) w / (values.size - 1) else w
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = i * step
                val y = h - (v / max) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = channel, style = Stroke(width = 4f, cap = StrokeCap.Round))
        }
    }
}

/** A small filled dot — used inline for channel legends. */
@Composable
fun ChannelDot(color: Color, size: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}
