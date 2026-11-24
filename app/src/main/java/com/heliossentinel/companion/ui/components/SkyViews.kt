package com.heliossentinel.companion.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.heliossentinel.companion.domain.SunPosition
import com.heliossentinel.companion.ui.theme.SkyBlue
import com.heliossentinel.companion.ui.theme.SunAmber
import kotlin.math.cos
import kotlin.math.sin

/**
 * Top-down sky map: the outer ring is the horizon, the centre is straight
 * overhead, north is up. Shows where the sun is (amber) and where the panel
 * is pointing (blue) — the gap between them is the tracking error.
 */
@Composable
fun SkyCompass(sun: SunPosition?, panel: SunPosition?, modifier: Modifier = Modifier) {
    val ringColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("N", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("W", style = MaterialTheme.typography.labelLarge)
            Canvas(
                modifier = Modifier
                    .padding(6.dp)
                    .size(180.dp)
                    .aspectRatio(1f)
            ) {
                val radius = size.minDimension / 2f
                val c = center
                listOf(1f, 2f / 3f, 1f / 3f).forEach { f ->
                    drawCircle(ringColor, radius * f, c, style = Stroke(width = 2f))
                }
                drawLine(ringColor, Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), 1.5f)
                drawLine(ringColor, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), 1.5f)

                fun toOffset(p: SunPosition): Offset {
                    val r = ((90.0 - p.elevationDeg.coerceIn(0.0, 90.0)) / 90.0 * radius).toFloat()
                    val a = Math.toRadians(p.azimuthDeg)
                    return Offset(c.x + r * sin(a).toFloat(), c.y - r * cos(a).toFloat())
                }

                val sunOffset = sun?.takeIf { it.isAboveHorizon }?.let(::toOffset)
                val panelOffset = panel?.let(::toOffset)
                if (sunOffset != null && panelOffset != null) {
                    drawLine(Color.Gray.copy(alpha = 0.6f), sunOffset, panelOffset, 3f)
                }
                sunOffset?.let {
                    drawCircle(SunAmber.copy(alpha = 0.3f), 22f, it)
                    drawCircle(SunAmber, 13f, it)
                }
                panelOffset?.let {
                    drawCircle(SkyBlue, 10f, it)
                    drawCircle(Color.White, 4f, it)
                }
            }
            Text("E", style = MaterialTheme.typography.labelLarge)
        }
        Text("S", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 6.dp)) {
            LegendDot(SunAmber, "Sun")
            LegendDot(SkyBlue, "Panel")
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 2×2 heat grid of the four LDRs, laid out the way they sit on the panel. */
@Composable
fun LightGrid(tl: Int, tr: Int, bl: Int, br: Int, adcMax: Int, modifier: Modifier = Modifier) {
    val scale = maxOf(tl, tr, bl, br, 1).coerceAtMost(adcMax).toFloat()
    @Composable
    fun Cell(label: String, value: Int, cellModifier: Modifier) {
        val intensity = (value / scale).coerceIn(0f, 1f)
        Box(
            modifier = cellModifier
                .aspectRatio(1.6f)
                .background(SunAmber.copy(alpha = 0.12f + 0.78f * intensity), RoundedCornerShape(10.dp))
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value.toString(), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(label, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Cell("Top-left", tl, Modifier.weight(1f))
            Cell("Top-right", tr, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Cell("Bottom-left", bl, Modifier.weight(1f))
            Cell("Bottom-right", br, Modifier.weight(1f))
        }
    }
}
