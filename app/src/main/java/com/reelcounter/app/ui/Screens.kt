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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reelcounter.app.AppSettings
import com.reelcounter.app.ReelCounterService
import com.reelcounter.app.ReelRepository
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

private val OverGoalRed = Color(0xFFFF4D4D)

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
    val goal by AppSettings.goal.collectAsStateWithLifecycle()

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
        if (tab == 0) HomeScreen(counts, goal, Modifier.padding(padding))
        else HistoryScreen(counts, goal, Modifier.padding(padding))
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
fun HomeScreen(counts: Map<String, Int>, goal: Int, modifier: Modifier = Modifier) {
    val today = rememberToday()
    val todayCount = counts[today.toString()] ?: 0
    val serviceOn by rememberServiceEnabled()

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!serviceOn) SetupCard()
        TodayCard(todayCount, goal, today)
        WeekCard(counts, goal, today)
        PopupCard(todayCount, goal)
        WatchRuleCard()
    }
}

@Composable
private fun TodayCard(count: Int, goal: Int, today: LocalDate) {
    var editGoal by remember { mutableStateOf(false) }
    val over = count > goal
    val numberColor = if (over) OverGoalRed else MaterialTheme.colorScheme.onSurface

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.labelLarge)
            Text(count.toString(), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold, color = numberColor)
            Text("reels watched today", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { min(1f, count.toFloat() / goal) },
                color = if (over) OverGoalRed else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (over) "Over your goal of $goal by ${count - goal}" else "Goal $goal  ·  ${goal - count} to go",
                style = MaterialTheme.typography.bodyMedium,
                color = if (over) OverGoalRed else MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { editGoal = true }) { Text("Change goal") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Fix count", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = { ReelRepository.adjustToday(-1) }, enabled = count > 0) { Text("−1") }
                OutlinedButton(onClick = { ReelRepository.adjustToday(1) }) { Text("+1") }
            }
        }
    }

    if (editGoal) {
        var text by remember { mutableStateOf(goal.toString()) }
        AlertDialog(
            onDismissRequest = { editGoal = false },
            title = { Text("Daily reel goal") },
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
                    text.toIntOrNull()?.let { AppSettings.setGoal(it) }
                    editGoal = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { AppSettings.setGoal(AppSettings.DEFAULT_GOAL); editGoal = false }) { Text("Reset to ${AppSettings.DEFAULT_GOAL}") }
            }
        )
    }
}

@Composable
private fun PopupCard(count: Int, goal: Int) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Transparency ${(transparency * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                // Live preview of how the popup will look
                Box(
                    Modifier.alpha(1f - transparency).clip(RoundedCornerShape(50)).background(Color(0xFF111111))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text("▶ $count", color = if (count > goal) OverGoalRed else Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                }
            }
            Slider(
                value = transparency,
                onValueChange = { AppSettings.setTransparency(it) },
                valueRange = 0f..AppSettings.MAX_TRANSPARENCY
            )
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
                "A reel counts once you've watched about half of it. Going back up and down again never counts the same reel twice.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "If your Instagram version doesn't share playback progress, a reel counts after this many seconds on it instead:",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { AppSettings.setFallbackSeconds(seconds - 1) }, enabled = seconds > 3) { Text("−") }
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
private fun WeekCard(counts: Map<String, Int>, goal: Int, today: LocalDate) {
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val values = days.map { counts[it.toString()] ?: 0 }
    val max = values.max().coerceAtLeast(1)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Last 7 days", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                days.forEachIndexed { i, d ->
                    val barColor = if (values[i] > goal) OverGoalRed else MaterialTheme.colorScheme.primary
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
            Text("Week total $total  ·  avg ${total / 7} per day  ·  red = over goal", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------- History

@Composable
fun HistoryScreen(counts: Map<String, Int>, goal: Int, modifier: Modifier = Modifier) {
    val today = rememberToday()
    val first = counts.keys.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.minOrNull() ?: today
    val days = generateSequence(today) { it.minusDays(1) }.takeWhile { !it.isBefore(first) }.toList()
    val total = counts.values.sum()
    val best = counts.values.maxOrNull() ?: 0
    val barMax = best.coerceAtLeast(1)

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Total", total.toString(), Modifier.weight(1f))
                StatCard("Avg / day", (total / days.size).toString(), Modifier.weight(1f))
                StatCard("Best day", best.toString(), Modifier.weight(1f))
            }
        }
        if (total == 0) item {
            Text("No reels counted yet. Open Instagram's Reels and start watching.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        }
        items(days, key = { it.toString() }) { d ->
            val c = counts[d.toString()] ?: 0
            val over = c > goal
            val label = when (d) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> d.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
            }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        LinearProgressIndicator(
                            progress = { c.toFloat() / barMax },
                            color = if (over) OverGoalRed else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        c.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        color = if (over) OverGoalRed else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.labelMedium)
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
