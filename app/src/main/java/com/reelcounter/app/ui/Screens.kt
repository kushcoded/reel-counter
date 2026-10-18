package com.reelcounter.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reelcounter.app.AppSettings
import com.reelcounter.app.Distance
import com.reelcounter.app.ReelCounterService
import com.reelcounter.app.ReelRepository
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

private val OverLimitRed = Color(0xFFFF4D4D)
private val DATE_LONG: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM")
private val DATE_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")
private val DATE_DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

@Composable
fun ReelCounterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val counts by ReelRepository.counts.collectAsStateWithLifecycle()
    val limit by AppSettings.limit.collectAsStateWithLifecycle()

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
        if (tab == 0) HomeScreen(counts, limit, Modifier.padding(padding))
        else HistoryScreen(counts, limit, Modifier.padding(padding))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            text = { Text("This deletes every daily total. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = { ReelRepository.clearAll(); confirmClear = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

// ---------------------------------------------------------------------------- Home

@Composable
fun HomeScreen(counts: Map<String, Int>, limit: Int, modifier: Modifier = Modifier) {
    val today = rememberToday()
    val todayCount = counts[today.toString()] ?: 0
    val totalReels = remember(counts) { counts.values.sum() }
    val serviceOn by rememberServiceEnabled()

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!serviceOn) SetupCard()
        TodayCard(todayCount, limit, today)
        LimitCard(limit)
        DistanceCard(todayCount, totalReels)
        WeekCard(counts, limit, today)
        PopupCard(todayCount, limit)
        WatchRuleCard()
    }
}

@Composable
private fun TodayCard(count: Int, limit: Int, today: LocalDate) {
    val over = count > limit
    val numberColor = if (over) OverLimitRed else MaterialTheme.colorScheme.onSurface

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(today.format(DATE_LONG), style = MaterialTheme.typography.labelLarge)
            Text(count.toString(), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold, color = numberColor)
            Text("reels watched today", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { min(1f, count.toFloat() / limit) },
                color = if (over) OverLimitRed else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (over) "Over your daily limit of $limit by ${count - limit}" else "Daily limit $limit  ·  ${limit - count} left",
                style = MaterialTheme.typography.bodyMedium,
                color = if (over) OverLimitRed else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Fix count", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = { ReelRepository.adjustToday(-1) }, enabled = count > 0) { Text("−1") }
                OutlinedButton(onClick = { ReelRepository.adjustToday(1) }) { Text("+1") }
            }
        }
    }
}

@Composable
private fun LimitCard(limit: Int) {
    var edit by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Daily limit", style = MaterialTheme.typography.titleMedium)
                    Text("The popup turns red when you go over", style = MaterialTheme.typography.bodySmall)
                }
                Text("$limit", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { AppSettings.setLimit(limit - 10) }, enabled = limit > 1, modifier = Modifier.weight(1f)) { Text("−10") }
                Button(onClick = { edit = true }, modifier = Modifier.weight(2f)) { Text("Set limit") }
                OutlinedButton(onClick = { AppSettings.setLimit(limit + 10) }, modifier = Modifier.weight(1f)) { Text("+10") }
            }
        }
    }

    if (edit) {
        var text by remember { mutableStateOf(limit.toString()) }
        AlertDialog(
            onDismissRequest = { edit = false },
            title = { Text("Daily reel limit") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(5) },
                    label = { Text("Reels per day") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toIntOrNull()?.let { AppSettings.setLimit(it) }
                    edit = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { AppSettings.setLimit(AppSettings.DEFAULT_LIMIT); edit = false }) { Text("Reset to ${AppSettings.DEFAULT_LIMIT}") }
            }
        )
    }
}

@Composable
private fun DistanceCard(todayReels: Int, totalReels: Int) {
    val fact = remember(totalReels) { Distance.funFact(totalReels) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Distance scrolled", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DistanceStat("Today", Distance.formatMeters(Distance.meters(todayReels)), Modifier.weight(1f))
                DistanceStat("All time", Distance.formatKm(Distance.kilometers(totalReels)), Modifier.weight(1f))
            }
            Text(fact.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            fact.next?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Based on ${Distance.CM_PER_REEL.toInt()} cm of scrolling per reel", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DistanceStat(title: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PopupCard(count: Int, limit: Int) {
    val overlayOn by AppSettings.overlayEnabled.collectAsStateWithLifecycle()
    val transparency by AppSettings.transparency.collectAsStateWithLifecycle()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Floating counter", style = MaterialTheme.typography.titleMedium)
                    Text("Small draggable popup while you're in Instagram", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = overlayOn, onCheckedChange = { AppSettings.setOverlayEnabled(it) })
            }
            Text("Background transparency ${(transparency * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
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
            Text("Only the dark background fades; the number stays solid.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WatchRuleCard() {
    val seconds by AppSettings.fallbackSeconds.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What counts as a reel", style = MaterialTheme.typography.titleMedium)
            Text(
                "A reel counts once you've watched about 30% of it. Going back up and down again never counts the same reel twice.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "If your Instagram version doesn't share playback progress, a reel counts after this many seconds on it instead:",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { AppSettings.setFallbackSeconds(seconds - 1) }, enabled = seconds > 1) { Text("−") }
                Text("$seconds s", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { AppSettings.setFallbackSeconds(seconds + 1) }, enabled = seconds < 30) { Text("+") }
            }
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
                "Enable Reel Counter under Accessibility → Installed apps so it can notice which reel you're watching in Instagram. Everything stays on your phone.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text("Open Accessibility settings")
            }
            if (Build.VERSION.SDK_INT >= 33) {
                Text(
                    "Switch greyed out? On Android 13+, open App info, tap ⋮ and choose “Allow restricted settings”.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }) { Text("Open App info") }
            }
        }
    }
}

@Composable
private fun WeekCard(counts: Map<String, Int>, limit: Int, today: LocalDate) {
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val values = days.map { counts[it.toString()] ?: 0 }
    val max = values.max().coerceAtLeast(1)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Last 7 days", style = MaterialTheme.typography.titleMedium)
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
            val total = values.sum()
            Text("Week total $total  ·  avg ${total / 7} per day  ·  red = over limit", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------- History

@Composable
fun HistoryScreen(counts: Map<String, Int>, limit: Int, modifier: Modifier = Modifier) {
    val today = rememberToday()
    val first = counts.keys.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.minOrNull() ?: today
    val days = remember(first, today) { generateSequence(today) { it.minusDays(1) }.takeWhile { !it.isBefore(first) }.toList() }
    val total = counts.values.sum()
    val worst = counts.values.maxOrNull() ?: 0
    val barMax = worst.coerceAtLeast(1)
    val worstDate = counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key?.let {
        runCatching { LocalDate.parse(it).format(DATE_DAY_MONTH) }.getOrNull()
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Total", total.toString(), Modifier.weight(1f), Distance.formatKm(Distance.kilometers(total)))
                StatCard("Avg / day", (total / days.size).toString(), Modifier.weight(1f))
                StatCard("Worst day", worst.toString(), Modifier.weight(1f), worstDate)
            }
        }
        if (total == 0) item {
            Text("No reels counted yet. Open Instagram's Reels and start watching.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        }
        items(days, key = { it.toString() }) { d ->
            val c = counts[d.toString()] ?: 0
            val over = c > limit
            val label = when (d) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> d.format(DATE_SHORT)
            }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(
                                Distance.formatMeters(Distance.meters(c)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LinearProgressIndicator(
                            progress = { c.toFloat() / barMax },
                            color = if (over) OverLimitRed else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        c.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        color = if (over) OverLimitRed else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier, subtitle: String? = null) {
    Card(modifier) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.labelMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---------------------------------------------------------------------------- Helpers

@Composable
private fun rememberToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); today = LocalDate.now() } }
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
