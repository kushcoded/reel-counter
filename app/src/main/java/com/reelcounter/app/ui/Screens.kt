package com.reelcounter.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reelcounter.app.AppSettings
import com.reelcounter.app.Distance
import com.reelcounter.app.Platform
import com.reelcounter.app.ReelRepository
import com.reelcounter.app.ReelCounterService
import com.reelcounter.app.TrackingState
import com.reelcounter.app.TrackingStatus
import com.reelcounter.app.WatchTime
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val OverLimitRed = Color(0xFFFF4D4D)
private val DATE_LONG: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM")
private val DATE_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")
private val DATE_DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

@Composable
fun ReelCounterTheme(dark: Boolean, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------------------------------------------------------------------------- Root

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val counts by ReelRepository.store.counts.collectAsStateWithLifecycle()
    val watch by ReelRepository.store.watchMs.collectAsStateWithLifecycle()
    val limit by AppSettings.limit.collectAsStateWithLifecycle()
    val cmPerReel by AppSettings.cmPerReel.collectAsStateWithLifecycle()
    val dark by AppSettings.darkTheme.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (tab == 0) "Reel Counter" else "History") },
                actions = {
                    if (tab == 1 && counts.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear history")
                        }
                    }
                    IconButton(onClick = { AppSettings.setDarkTheme(!dark) }) {
                        ThemeIcon(
                            dark,
                            Modifier.semantics { contentDescription = if (dark) "Switch to light theme" else "Switch to dark theme" }
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) }, label = { Text("Today") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.DateRange, contentDescription = null) }, label = { Text("History") }
                )
            }
        }
    ) { padding ->
        if (tab == 0) HomeScreen(counts, watch, limit, cmPerReel, Modifier.padding(padding))
        else HistoryScreen(counts, watch, limit, cmPerReel, Modifier.padding(padding))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            text = { Text("This deletes every daily total and all watch time.") },
            confirmButton = {
                TextButton(onClick = { ReelRepository.store.clearAll(); confirmClear = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

// ---------------------------------------------------------------------------- Shared bits

/** Plain black-and-white icon of the current theme: a moon when dark, a sun when light. */
@Composable
private fun ThemeIcon(dark: Boolean, modifier: Modifier = Modifier) {
    val color = if (dark) Color.White else Color.Black
    Canvas(modifier.size(22.dp)) {
        val r = size.minDimension / 2
        if (dark) {
            val moon = Path().apply { addOval(Rect(center, r * 0.85f)) }
            val bite = Path().apply { addOval(Rect(Offset(center.x + r * 0.5f, center.y - r * 0.3f), r * 0.75f)) }
            drawPath(Path.combine(PathOperation.Difference, moon, bite), color)
        } else {
            drawCircle(color, radius = r * 0.42f, center = center)
            for (i in 0 until 8) {
                val a = i * PI / 4
                val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
                drawLine(color, center + dir * (r * 0.68f), center + dir * (r * 0.98f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

/** Small (i) button; the explanation lives in a dialog so cards stay uncluttered. */
@Composable
private fun InfoButton(title: String, text: String) {
    var show by remember { mutableStateOf(false) }
    IconButton(onClick = { show = true }, modifier = Modifier.size(40.dp)) {
        Icon(
            Icons.Default.Info, contentDescription = "About $title",
            modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { show = false }) { Text("Got it") } }
        )
    }
}

@Composable
private fun CardTitle(title: String, info: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (info != null) {
            Spacer(Modifier.width(2.dp))
            InfoButton(title, info)
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

// ---------------------------------------------------------------------------- Today tab

@Composable
fun HomeScreen(counts: Map<String, Int>, watch: Map<String, Long>, limit: Int, cmPerReel: Float, modifier: Modifier = Modifier) {
    val today = rememberToday()
    val todayCount = counts[today.toString()] ?: 0
    val totalReels = remember(counts) { counts.values.sum() }
    val todayWatch = watch[today.toString()] ?: 0L
    val totalWatch = remember(watch) { watch.values.sum() }
    val watchDays = remember(watch) { watch.values.count { it > 0 }.coerceAtLeast(1) }
    // Pair watch time with the reels counted on the same days, so history from before watch-time
    // tracking existed doesn't drag the per-reel average down.
    val reelsOnWatchedDays = remember(counts, watch) {
        counts.entries.sumOf { if ((watch[it.key] ?: 0L) > 0L) it.value else 0 }
    }
    val serviceOn by rememberServiceEnabled()

    Column(
        modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!serviceOn) SetupCard()
        if (serviceOn) TrackingStatusCard()
        TodayCard(todayCount, limit, today)
        LimitCard(limit)
        WeekCard(counts, limit, today)
        WatchTimeCard(todayWatch, totalWatch, totalWatch / watchDays, todayCount, reelsOnWatchedDays)
        DistanceCard(todayCount, totalReels, cmPerReel)
        PopupCard(todayCount, limit)
        WatchRuleCard()
        SourcesCard()
        Footer()
    }
}

@Composable
private fun TodayCard(count: Int, limit: Int, today: LocalDate) {
    val over = count > limit
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(today.format(DATE_LONG), style = MaterialTheme.typography.labelLarge)
            Text(
                count.toString(), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold,
                color = if (over) OverLimitRed else MaterialTheme.colorScheme.onSurface
            )
            Text("Reels & Shorts today", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { min(1f, count.toFloat() / limit) },
                color = if (over) OverLimitRed else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (over) "$count / $limit · ${count - limit} over limit" else "$count / $limit · ${limit - count} remaining",
                style = MaterialTheme.typography.bodyMedium,
                color = if (over) OverLimitRed else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LimitCard(limit: Int) {
    var edit by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle(
                "Daily limit",
                "Your limit applies to the combined Reels and Shorts count. It changes the progress display but does not block either app."
            ) {
                Text("$limit", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            }
            Button(onClick = { edit = true }, modifier = Modifier.fillMaxWidth()) { Text("Set limit") }
        }
    }

    if (edit) {
        var text by remember { mutableStateOf(limit.toString()) }
        val focus = remember { FocusRequester() }
        val save = {
            text.toIntOrNull()?.let { AppSettings.setLimit(it) }
            edit = false
        }
        AlertDialog(
            onDismissRequest = { edit = false },
            title = { Text("Daily limit") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(5) },
                    label = { Text("Reels + Shorts per day") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.focusRequester(focus)
                )
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { text = AppSettings.DEFAULT_LIMIT.toString() }) {
                        Text("Use default")
                    }
                    TextButton(onClick = { save() }) { Text("Save") }
                }
            },
            dismissButton = { TextButton(onClick = { edit = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun WeekCard(counts: Map<String, Int>, limit: Int, today: LocalDate) {
    val days = remember(today) { (6 downTo 0).map { today.minusDays(it.toLong()) } }
    val values = remember(counts, today) { days.map { counts[it.toString()] ?: 0 } }
    val max = values.max().coerceAtLeast(1)
    val total = values.sum()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle("Last 7 days")
            Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                days.forEachIndexed { i, d ->
                    val barColor = if (values[i] > limit) OverLimitRed else MaterialTheme.colorScheme.primary
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Text(values[i].toString(), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(2.dp))
                        Box(
                            Modifier.fillMaxWidth().height((80f * values[i] / max).coerceAtLeast(3f).dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(barColor.copy(alpha = if (i == 6) 1f else 0.5f))
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text("Total $total  ·  Average ${total / 7} per day", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WatchTimeCard(
    todayMs: Long, totalMs: Long, dailyAvgMs: Long,
    todayReels: Int, reelsOnWatchedDays: Int
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle("Watch time")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBlock("Today", WatchTime.format(todayMs), Modifier.weight(1f), compact = true)
                StatBlock("All time", WatchTime.format(totalMs), Modifier.weight(1f), compact = true)
                StatBlock("Daily avg", WatchTime.format(dailyAvgMs), Modifier.weight(1f), compact = true)
            }
            HorizontalDivider()
            Text("Average watch time", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatBlock("Today", WatchTime.formatAvg(todayMs, todayReels), Modifier.weight(1f), compact = true)
                StatBlock("All time", WatchTime.formatAvg(totalMs, reelsOnWatchedDays), Modifier.weight(1f), compact = true)
            }
        }
    }
}

@Composable
private fun DistanceCard(todayReels: Int, totalReels: Int, cmPerReel: Float) {
    val fact = remember(totalReels, cmPerReel) { Distance.funFact(totalReels, cmPerReel) }
    val info = buildString {
        append("Estimated distance = counted Reels and Shorts × your chosen scroll length. Changing the length also recalculates past estimates.")
        fact.next?.let { append("\n\n").append(it) }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle("Estimated scroll distance", info)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatBlock("Today", Distance.formatMeters(Distance.meters(todayReels, cmPerReel)), Modifier.weight(1f))
                StatBlock("All time", Distance.formatKm(Distance.kilometers(totalReels, cmPerReel)), Modifier.weight(1f))
            }
            Text(fact.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            HorizontalDivider()
            Text(
                "Estimated scroll per video: ${String.format(Locale.getDefault(), "%.1f", cmPerReel)} cm",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = cmPerReel,
                onValueChange = { AppSettings.setCmPerReel(it) },
                onValueChangeFinished = { AppSettings.persistCmPerReel() },
                valueRange = AppSettings.MIN_CM_PER_REEL..AppSettings.MAX_CM_PER_REEL,
                steps = 13   // 0.5 cm steps from 10 to 17
            )
        }
    }
}

@Composable
private fun StatBlock(title: String, value: String, modifier: Modifier, compact: Boolean = false) {
    Column(modifier) {
        Text(
            value,
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, maxLines = 1
        )
        Text(title, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PopupCard(count: Int, limit: Int) {
    val overlayOn by AppSettings.overlayEnabled.collectAsStateWithLifecycle()
    val transparency by AppSettings.transparency.collectAsStateWithLifecycle()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(
                "Floating counter",
                "Shows a small draggable count over Instagram and YouTube Shorts. Its position is saved. Transparency affects only the background, not the number."
            ) {
                Switch(checked = overlayOn, onCheckedChange = { AppSettings.setOverlayEnabled(it) })
            }
            Text("Popup background transparency ${(transparency * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
            // Live preview over a mid-tone backdrop, to show how the popup reads on top of video
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF7C8594)).padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF111111).copy(alpha = 1f - transparency))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        "▶ $count",
                        color = if (count > limit) OverLimitRed else Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge.copy(shadow = Shadow(Color.Black, blurRadius = 6f))
                    )
                }
            }
            Slider(
                value = transparency,
                onValueChange = { AppSettings.setTransparency(it) },
                onValueChangeFinished = { AppSettings.persistTransparency() },
                valueRange = 0f..AppSettings.MAX_TRANSPARENCY
            )
        }
    }
}

@Composable
private fun WatchRuleCard() {
    val seconds by AppSettings.seconds.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(
                "Counting rule",
                "A Reel or Short counts after it stays on screen for this long. Revisiting the same item during the current feed session does not count it again."
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Minimum watch time", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { AppSettings.setSeconds(seconds - 1) }, enabled = seconds > 1) { Text("−") }
                Text("$seconds s", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp))
                OutlinedButton(onClick = { AppSettings.setSeconds(seconds + 1) }, enabled = seconds < 30) { Text("+") }
            }
        }
    }
}

/** One switch per app. Off = the service ignores that app completely (no counting, no popup). */
@Composable
private fun SourcesCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CardTitle("Tracked apps", "Turn an app off to stop counting and hide the floating counter in that app.")
            Platform.entries.forEach { p ->
                val on by AppSettings.tracking(p).collectAsStateWithLifecycle()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.appName} ${p.label}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = { AppSettings.setTracking(p, it) })
                }
            }
        }
    }
}

@Composable
private fun Footer() {
    Text(
        "Licensed under GPLv3.0",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    )
}

@Composable
private fun TrackingStatusCard() {
    val snapshot by TrackingStatus.snapshot.collectAsStateWithLifecycle()
    val (title, detail) = when {
        snapshot.state == TrackingState.FEED_ACTIVE -> {
            val p = snapshot.platform
            "Tracking active" to if (p != null) "Counting ${p.appName} ${p.label}." else "Counting the current short-video feed."
        }
        snapshot.state == TrackingState.APP_ACTIVE -> {
            val p = snapshot.platform
            "App detected" to if (p != null) "${p.appName} is open. Waiting for ${p.label}." else "Waiting for a short-video feed."
        }
        snapshot.state == TrackingState.TRANSITIONING -> "Checking app" to "Verifying the active screen after an app switch."
        else -> "Ready" to "Tracking starts automatically when you open Instagram Reels or YouTube Shorts."
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SetupCard() {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Warning, contentDescription = null)
                Text("Turn on the counter", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Enable Reel Counter in Accessibility settings so it can recognize Reels and Shorts. Counts and watch time stay on this device.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text("Open Accessibility settings")
            }
            if (Build.VERSION.SDK_INT >= 33) {
                Text("Greyed out? App info → ⋮ → Allow restricted settings.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }) { Text("Open App info") }
            }
        }
    }
}

// ---------------------------------------------------------------------------- History tab

@Composable
fun HistoryScreen(counts: Map<String, Int>, watch: Map<String, Long>, limit: Int, cmPerReel: Float, modifier: Modifier = Modifier) {
    val today = rememberToday()
    // Only days that have reels, plus today; newest first.
    val rows = remember(counts, today) {
        counts.filter { it.value > 0 }.keys
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .plus(today).distinct().sortedDescending()
    }
    val total = remember(counts) { counts.values.sum() }
    val activeDays = remember(counts) { counts.values.count { it > 0 }.coerceAtLeast(1) }
    val dailyAvg = total / activeDays
    val worst = counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }
    val barMax = (worst?.value ?: 0).coerceAtLeast(1)
    val daysOver = counts.count { it.value > limit }
    val worstDate = worst?.key?.let { runCatching { LocalDate.parse(it).format(DATE_DAY_MONTH) }.getOrNull() } ?: "–"

    LazyColumn(
        modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            // 2x2 grid; every card has the same three lines (value, title, detail) so heights match.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("Total", total.toString(), Distance.formatKm(Distance.kilometers(total, cmPerReel)), Modifier.weight(1f).fillMaxHeight())
                    StatCard("Daily avg", dailyAvg.toString(), Distance.formatMeters(Distance.meters(dailyAvg, cmPerReel)), Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("Worst day", (worst?.value ?: 0).toString(), worstDate, Modifier.weight(1f).fillMaxHeight())
                    StatCard(
                        "Days over limit", daysOver.toString(), "of $activeDays days", Modifier.weight(1f).fillMaxHeight(),
                        valueColor = if (daysOver > 0) OverLimitRed else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        if (total == 0) {
            item {
                Text(
                    "No reels or shorts counted yet.\nScroll through Reels or Shorts and they'll show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                )
            }
        } else {
            items(rows, key = { it.toString() }) { d ->
                val c = counts[d.toString()] ?: 0
                val over = c > limit
                val label = when (d) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> d.format(DATE_SHORT)
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                                val w = watch[d.toString()] ?: 0L
                                val dist = Distance.formatMeters(Distance.meters(c, cmPerReel))
                                Text(
                                    if (w > 0) "$dist  ·  ${WatchTime.format(w)}" else dist,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                c.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                                color = if (over) OverLimitRed else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        LinearProgressIndicator(
                            progress = { c.toFloat() / barMax },
                            color = if (over) OverLimitRed else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        )
                    }
                }
            }
        }
        item { Footer() }
    }
}

@Composable
private fun StatCard(
    title: String, value: String, detail: String, modifier: Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 1)
            Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------------------- Helpers

@Composable
private fun rememberToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) today = LocalDate.now() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val now = java.time.ZonedDateTime.now()
            val nextDay = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            val waitMs = java.time.Duration.between(now, nextDay).toMillis().coerceAtLeast(1_000L)
            delay(waitMs)
            today = LocalDate.now()
        }
    }
    return today
}

@Composable
private fun rememberServiceEnabled(): State<Boolean> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(isServiceEnabled(ctx)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) state.value = isServiceEnabled(ctx) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return state
}

private fun isServiceEnabled(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val me = ComponentName(ctx, ReelCounterService::class.java)
    return enabled.split(':').any {
        it.equals(me.flattenToString(), ignoreCase = true) || it.equals(me.flattenToShortString(), ignoreCase = true)
    }
}
