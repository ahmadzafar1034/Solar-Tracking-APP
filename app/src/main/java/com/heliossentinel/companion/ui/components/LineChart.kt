package com.heliossentinel.companion.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** One named numeric series to plot. NaN values are gaps (e.g. tracking error at night). */
data class ChartSeries(val label: String, val color: Color, val values: List<Float>)

/**
 * A minimal, dependency-free line chart drawn with Compose's [Canvas].
 * Avoids an external charting library so the project builds without
 * pinning a third-party artifact version.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiLineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    yLabelFormatter: (Float) -> String = { it.toInt().toString() },
    startLabel: String? = null,
    endLabel: String? = null
) {
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Column(modifier = modifier) {
        FlowRow(
            modifier = Modifier.padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            series.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(modifier = Modifier.size(10.dp)) { drawCircle(color = s.color) }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = s.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        val finite = series.flatMap { it.values }.filter { it.isFinite() }
        if (finite.isEmpty()) {
            Text("No data to plot for this range.", style = MaterialTheme.typography.bodyMedium, color = labelColor)
        } else {
            val minY = finite.min()
            val maxY = finite.max().let { if (it == minY) it + 1f else it }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                val w = size.width
                val h = size.height
                val gridColor = Color.Gray.copy(alpha = 0.2f)
                for (i in 0..4) {
                    val y = h * i / 4
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), 1f)
                }

                series.forEach { s ->
                    if (s.values.size < 2) return@forEach
                    val stepX = w / (s.values.size - 1)
                    val path = Path()
                    var penDown = false
                    s.values.forEachIndexed { index, value ->
                        if (!value.isFinite()) {
                            penDown = false // leave a gap
                            return@forEachIndexed
                        }
                        val x = stepX * index
                        val y = h - ((value - minY) / (maxY - minY)) * h
                        if (penDown) path.lineTo(x, y) else path.moveTo(x, y)
                        penDown = true
                    }
                    drawPath(path = path, color = s.color, style = Stroke(width = 4f))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(startLabel.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = labelColor)
                Text(
                    "min ${yLabelFormatter(minY)} · max ${yLabelFormatter(maxY)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = labelColor
                )
                Text(endLabel.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = labelColor)
            }
        }
    }
}
