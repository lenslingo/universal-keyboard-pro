package com.ukpro.android.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ukpro.android.UkRuntime
import com.ukpro.android.service.ConnectionService
import kotlinx.coroutines.launch

enum class Screen(val title: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home), Keyboard("Keyboard", Icons.Filled.Keyboard),
    Universal("Universal text (any language)", Icons.Filled.Translate),
    Remote("Remote Control", Icons.Filled.SettingsRemote), Clipboard("Clipboard", Icons.Filled.ContentPaste),
    Files("File Transfer", Icons.Filled.FolderCopy), Devices("Connected Devices", Icons.Filled.Devices),
    History("History", Icons.Filled.History), Settings("Settings", Icons.Filled.Settings),
}

private val Accent = Color(0xFF5CC8FF)
private val Warm = Color(0xFFFFB040)

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UkRuntime.init(this)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (UkRuntime.secrets.hubs().isNotEmpty()) ConnectionService.start(this)
        setContent { UkTheme { App() } }
    }
}

@Composable
fun UkTheme(content: @Composable () -> Unit) {
    val dark by UkRuntime.settings.state.collectAsState()
    MaterialTheme(
        colorScheme = if (dark.darkTheme) darkColorScheme(primary = Accent, secondary = Warm, background = Color(0xFF0F1320),
            surface = Color(0xFF171C2E), surfaceVariant = Color(0xFF1E2438), onPrimary = Color(0xFF0F1320))
        else lightColorScheme(primary = Color(0xFF0A7BC2), secondary = Color(0xFFB86E00)),
        content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App() {
    var screen by remember { mutableStateOf(Screen.Home) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val msg by UkRuntime.messages.collectAsState()
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); UkRuntime.messages.value = null } }

    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet {
            Text("UK Pro", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp), fontWeight = FontWeight.Bold)
            for (s in Screen.values()) NavigationDrawerItem(
                label = { Text(s.title) }, icon = { Icon(s.icon, null) }, selected = s == screen,
                onClick = { screen = s; scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }) {
        Scaffold(
            topBar = { TopAppBar(title = { Text(screen.title) }, navigationIcon = {
                IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Menu") } }) },
            snackbarHost = { SnackbarHost(snack) },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                when (screen) {
                    Screen.Home -> HomeScreen { screen = it }
                    Screen.Keyboard -> KeyboardScreen()
                    Screen.Universal -> UniversalTextScreen()
                    Screen.Remote -> RemoteScreen()
                    Screen.Clipboard -> ClipboardScreen()
                    Screen.Files -> FilesScreen()
                    Screen.Devices -> DevicesScreen()
                    Screen.History -> HistoryScreen()
                    Screen.Settings -> SettingsScreen()
                }
            }
        }
    }
}

// ------------------------------------------------------------------ shared bits

@Composable
fun UkCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.padding(3.dp))
            content()
        }
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

// ------------------------------------------------------------------ Home

@Composable
private fun HomeScreen(go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val hubs by UkRuntime.hubs.collectAsState()
    val s by UkRuntime.settings.state.collectAsState()
    val transfers by UkRuntime.transferList.collectAsState()
    val active = hubs.firstOrNull { it.isActive } ?: hubs.firstOrNull()
    val imm = ctx.getSystemService(InputMethodManager::class.java)
    val imeEnabled = imm.enabledInputMethodList.any { it.packageName == ctx.packageName }
    val imeSelected = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(ctx.packageName) == true

    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            UkCard("Connection") {
                if (active == null) {
                    Text("No laptop paired yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.padding(4.dp))
                    Button(onClick = { go(Screen.Devices) }) { Text("Pair with Windows") }
                } else {
                    Text(if (active.connected) "● ${active.name}" else "○ ${active.name} — ${active.error ?: "connecting…"}",
                        style = MaterialTheme.typography.titleMedium, color = if (active.connected) Color(0xFF4CD69B) else MaterialTheme.colorScheme.onSurface)
                    Text("This phone: ${UkRuntime.secrets.deviceName}")
                    Text("Method: ${active.transport.uppercase()} · Latency: ${if (active.rttMs >= 0) "${active.rttMs} ms" else "—"}")
                    if (hubs.size > 1) Text("${hubs.count { it.connected }} of ${hubs.size} laptops connected")
                }
            }
        }
        item {
            UkCard("Keyboard") {
                if (!imeEnabled || !imeSelected) {
                    Text("UK Pro Keyboard must be enabled and selected for typing in both directions.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        if (!imeEnabled) Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }) { Text("1. Enable") }
                        OutlinedButton(onClick = { imm.showInputMethodPicker() }) { Text(if (imeEnabled) "Select keyboard" else "2. Select") }
                    }
                }
                ToggleRow("Phone keyboard → PC", s.androidToWindows) { v -> UkRuntime.settings.update { it.copy(androidToWindows = v) } }
                Text("PC keyboard → phone: " + when {
                    active == null -> "—"
                    active.w2a && active.kbCaptureOnHub -> "ON (switch on the laptop)"
                    active.w2a -> "ON"
                    else -> "OFF (enable in the laptop's tray menu)"
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            UkCard("Clipboard") {
                ToggleRow("Sync text, images and files", s.clipboardSync) { v -> UkRuntime.settings.update { it.copy(clipboardSync = v) } }
                OutlinedButton(onClick = { ctx.startActivity(Intent(ctx, ClipboardSendActivity::class.java)) }) { Text("Send current clipboard now") }
            }
        }
        item {
            UkCard("File transfer") {
                if (transfers.isEmpty()) Text("Idle") else for (t in transfers)
                    Text("${if (t.incoming) "↓" else "↑"} ${t.name} — ${(t.fraction * 100).toInt()}% · ${"%.1f".format(t.bytesPerSecond / 1048576)} MB/s")
                Spacer(Modifier.padding(4.dp))
                OutlinedButton(onClick = { go(Screen.Files) }) { Text("Send files…") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                Button(onClick = { UkRuntime.setPaused(!s.paused) }) { Text(if (s.paused) "▶ Resume synchronization" else "⏸ Pause synchronization") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { go(Screen.Remote) }) { Text("Remote") }
            }
        }
    }
}
