package com.brianchen.locklist.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.brianchen.locklist.LockListApp
import com.brianchen.locklist.data.AppSettings
import com.brianchen.locklist.service.ScreenService
import com.brianchen.locklist.sync.SyncWorker
import com.brianchen.locklist.ui.theme.LockListTheme
import java.io.FileOutputStream
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val overlayOk = mutableStateOf(false)
    private val batteryOk = mutableStateOf(false)
    private val notifyOk = mutableStateOf(false)

    private val notifyLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshStatuses()
        startScreenService()
    }

    private val wallpaperPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val app = application as LockListApp
        val dest = app.settings.wallpaperFile()
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
        app.settings.markWallpaperChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as LockListApp
        setContent {
            val themeMode by app.settings.themeMode
            LockListTheme(themeMode = themeMode) {
                var signedIn by remember { mutableStateOf(false) }
                var email by remember { mutableStateOf<String?>(null) }
                val tasks by app.tasks.observeTasks().collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()
                LaunchedEffect(Unit) {
                    signedIn = app.sync.isSignedIn()
                    email = app.sync.currentEmail()
                }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(24.dp)
                            .fillMaxSize()
                    ) {
                        SetupScreen(
                            overlayOk = overlayOk,
                            batteryOk = batteryOk,
                            notifyOk = notifyOk,
                            onOverlay = { openOverlaySettings() },
                            onBattery = { openBatterySettings() },
                            onNotify = { openNotificationSettings() },
                            onStart = { onStartServiceClicked() }
                        )
                        Spacer(Modifier.height(16.dp))
                        AppearanceSection(
                            themeMode = themeMode,
                            onTheme = { app.settings.setThemeMode(it) },
                            onPickWallpaper = { wallpaperPicker.launch("image/*") },
                            onClearWallpaper = {
                                app.settings.wallpaperFile().delete()
                                app.settings.markWallpaperChanged()
                            }
                        )
                        Spacer(Modifier.height(16.dp))
                        if (!signedIn) {
                            LoginScreen(
                                sync = app.sync,
                                onSignedIn = {
                                    signedIn = true
                                    scope.launch {
                                        email = app.sync.currentEmail()
                                        SyncWorker.enqueueOneShot(this@MainActivity)
                                    }
                                }
                            )
                        } else {
                            Text(
                                "Signed in as ${email ?: "portal admin"}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        app.sync.signOut()
                                        signedIn = false
                                        email = null
                                    }
                                }
                            ) {
                                Text("Sign out")
                            }
                            Spacer(Modifier.height(8.dp))
                            EditorScreen(
                                repo = app.tasks,
                                tasks = tasks,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    private fun refreshStatuses() {
        overlayOk.value = Settings.canDrawOverlays(this)
        val power = getSystemService(POWER_SERVICE) as PowerManager
        batteryOk.value = power.isIgnoringBatteryOptimizations(packageName)
        notifyOk.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun onStartServiceClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifyOk.value) {
            notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startScreenService()
    }

    private fun startScreenService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, ScreenService::class.java)
        )
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openBatterySettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        )
    }
}

@Composable
private fun SetupScreen(
    overlayOk: State<Boolean>,
    batteryOk: State<Boolean>,
    notifyOk: State<Boolean>,
    onOverlay: () -> Unit,
    onBattery: () -> Unit,
    onNotify: () -> Unit,
    onStart: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("LockList setup", style = MaterialTheme.typography.headlineSmall)
        PermissionRow("Display over other apps", overlayOk.value, onOverlay)
        PermissionRow("Battery unrestricted", batteryOk.value, onBattery)
        PermissionRow("Notifications", notifyOk.value, onNotify)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("Start service")
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(if (granted) Color(0xFF2E7D32) else Color(0xFFC62828))
        )
        Button(onClick = onClick, modifier = Modifier.weight(1f)) {
            Text(label)
        }
    }
}

@Composable
private fun AppearanceSection(
    themeMode: String,
    onTheme: (String) -> Unit,
    onPickWallpaper: () -> Unit,
    onClearWallpaper: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = themeMode == AppSettings.THEME_SYSTEM,
                onClick = { onTheme(AppSettings.THEME_SYSTEM) },
                label = { Text("System") }
            )
            FilterChip(
                selected = themeMode == AppSettings.THEME_DARK,
                onClick = { onTheme(AppSettings.THEME_DARK) },
                label = { Text("Dark") }
            )
            FilterChip(
                selected = themeMode == AppSettings.THEME_LIGHT,
                onClick = { onTheme(AppSettings.THEME_LIGHT) },
                label = { Text("Light") }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickWallpaper) { Text("Set wallpaper") }
            TextButton(onClick = onClearWallpaper) { Text("Clear") }
        }
    }
}
