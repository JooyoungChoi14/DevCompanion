package com.devcompanion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.devcompanion.debug.BrowserDebuggerHolder
import com.devcompanion.debug.PerformanceMetric
import com.devcompanion.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceTab() {
    val debugger = BrowserDebuggerHolder.current
    val metrics by (debugger?.performanceMetrics
        ?: MutableStateFlow<List<PerformanceMetric>>(emptyList()))
        .collectAsState(initial = emptyList())

    val latestMetric = metrics.lastOrNull()
    val metricCount = metrics.size

    Column(modifier = Modifier.fillMaxSize().padding(Spacing.lg)) {
        // Status + Refresh
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (debugger != null) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (debugger != null) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (debugger != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    if (debugger != null) "Browser debugger active" else "Open browser tab to start debugging",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                if (debugger != null) {
                    IconButton(onClick = { debugger.collectPerformanceData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh metrics")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.lg))

        Text("Performance Metrics", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(Spacing.sm))

        if (latestMetric != null) {
            MetricCard("Page Load Time", "${latestMetric.loadTimeMs}ms", MaterialTheme.colorScheme.primary)
            if (latestMetric.jsHeapUsed > 0f) {
                MetricCard("JS Heap Used", formatBytes(latestMetric.jsHeapUsed), MaterialTheme.colorScheme.tertiary)
                MetricCard("JS Heap Total", formatBytes(latestMetric.jsHeapTotal), MaterialTheme.colorScheme.tertiary)
            }
            if (latestMetric.domNodes > 0) {
                MetricCard("DOM Nodes", latestMetric.domNodes.toString(), MaterialTheme.colorScheme.secondary)
            }
            if (latestMetric.fps > 0f) {
                MetricCard("FPS", String.format("%.1f", latestMetric.fps), MaterialTheme.colorScheme.secondary)
            }
            if (latestMetric.jsHeapUsed == 0f && latestMetric.domNodes == 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(Spacing.md)) {
                        Text("Limited data", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            "Heap and DOM data require the browser with --enable-precise-memory-info flag. " +
                            "Tap refresh after page load to collect DOM node count.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Text("No metrics yet", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        "Performance data is captured when pages load in the browser tab. " +
                        "Tap refresh to collect DOM and heap data on demand.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        if (metricCount > 0) {
            Text(
                "$metricCount metric(s) collected",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = Spacing.sm)
            )
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xxs),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = color
            )
        }
    }
}

private fun formatBytes(bytes: Float): String {
    if (bytes <= 0f) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
}