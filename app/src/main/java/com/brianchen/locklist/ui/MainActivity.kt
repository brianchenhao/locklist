package com.brianchen.locklist.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.brianchen.locklist.BuildConfig
import com.brianchen.locklist.LockListApp
import com.brianchen.locklist.data.AppSettings
import com.brianchen.locklist.service.ScreenService
import com.brianchen.locklist.ui.theme.LockListTheme
import com.brianchen.locklist.update.AppUpdate
import com.brianchen.locklist.update.UpdateInfo
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { saveWallpaper(uri) }
            if (!ok) {
                Toast.makeText(this@MainActivity, "Could not use that photo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LockListApp
        applySystemBars(isDarkTheme(app.settings.themeMode.value))
        refreshStatuses()
        setContent {
            val themeMode by app.settings.themeMode
            val darkTheme = when (themeMode) {
                AppSettings.THEME_DARK -> true
                AppSettings.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            // Bar icons follow the in-app theme, not the phone's, so they stay readable.
            LaunchedEffect(darkTheme) { applySystemBars(darkTheme) }
            LockListTheme(themeMode = themeMode) {
                // null = still checking; never flash the sign-in form at a signed-in user.
                var signedIn by remember { mutableStateOf<Boolean?>(null) }
                var email by remember { mutableStateOf<String?>(null) }
                val taskFlow = remember { app.tasks.observeTasks() }
                val tasks by taskFlow.collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()
                val updater = remember { AppUpdate() }
                var updateStatus by remember { mutableStateOf("Version ${BuildConfig.VERSION_NAME}") }
                var pendingUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
                var updateBusy by remember { mutableStateOf(false) }
                val liveStatus by app.sync.liveStatus.collectAsState()
                val health by app.sync.health.collectAsState()
                val now = rememberNow()
                // null = follow the automatic rule below until the user opens or closes it.
                var settingsChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
                val allSet = overlayOk.value && batteryOk.value && notifyOk.value
                // Only an update already known when the app opened opens the panel, so it is
                // open from the first frame. One found by this session's check (a second or two
                // later) must not push the board down under the user's finger; the summary
                // line says "Update ready" instead.
                val updateKnownAtStart = remember { knownUpdateCode() > BuildConfig.VERSION_CODE }
                val settingsOpen = settingsChoice
                    ?: (!allSet || signedIn == false || updateKnownAtStart)
                val syncWarn = signedIn == true && syncNeedsAttention(
                    lastOkAt = health.lastOkAt,
                    lastError = health.lastError,
                    pending = health.pending,
                    now = now,
                    offline = health.offline
                )
                val checkForUpdate: () -> Unit = {
                    if (!updateBusy) {
                        updateBusy = true
                        updateStatus = "Checking…"
                        scope.launch {
                            try {
                                val latest = updater.checkLatest()
                                saveKnownUpdateCode(if (latest.isNewer) latest.versionCode else 0)
                                if (latest.isNewer) {
                                    pendingUpdate = latest
                                    updateStatus = "Update ${latest.versionName} available"
                                } else {
                                    pendingUpdate = null
                                    updateStatus = "Up to date (${BuildConfig.VERSION_NAME})"
                                }
                            } catch (e: Exception) {
                                pendingUpdate = null
                                updateStatus = e.message ?: "Update check failed"
                            } finally {
                                updateBusy = false
                            }
                        }
                    }
                }
                val installUpdate: () -> Unit = install@{
                    val latest = pendingUpdate ?: return@install
                    if (updateBusy) return@install
                    if (!updater.canInstall(this@MainActivity)) {
                        installPermissionLauncher.launch(
                            updater.installPermissionIntent(this@MainActivity)
                        )
                        updateStatus = "Allow LockList to install apps, then tap Install again"
                        return@install
                    }
                    updateBusy = true
                    updateStatus = "Downloading ${latest.versionName}…"
                    scope.launch {
                        try {
                            val apk = File(cacheDir, "updates/locklist.apk")
                            updater.downloadApk(latest.apkUrl, apk)
                            startActivity(updater.installIntent(this@MainActivity, apk))
                            updateStatus = "Install ${latest.versionName} when prompted"
                        } catch (e: Exception) {
                            updateStatus = e.message ?: "Download failed"
                        } finally {
                            updateBusy = false
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    signedIn = app.sync.isSignedIn()
                    email = app.sync.currentEmail()
                    checkForUpdate()
                }
                val warn = warningColor()
                val summary = buildAnnotatedString {
                    if (allSet) append("All set") else withStyle(SpanStyle(color = warn)) { append("Setup needed") }
                    append(" · ")
                    if (pendingUpdate != null) append("Update ready") else append("v${BuildConfig.VERSION_NAME}")
                    when (signedIn) {
                        null -> Unit
                        false -> append(" · Signed out")
                        true -> {
                            append(" · ")
                            val syncText = when {
                                !health.lastError.isNullOrBlank() && health.offline -> "Offline"
                                !health.lastError.isNullOrBlank() -> "Sync problem"
                                syncWarn -> "Not synced"
                                else -> syncAgo(health.lastOkAt, now, short = true)
                            }
                            if (syncWarn) withStyle(SpanStyle(color = warn)) { append(syncText) } else append(syncText)
                        }
                    }
                }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                            .fillMaxSize()
                    ) {
                        SettingsHeader(
                            open = settingsOpen,
                            summary = summary,
                            onToggle = { settingsChoice = !settingsOpen }
                        )
                        val login: @Composable () -> Unit = {
                            LoginScreen(
                                sync = app.sync,
                                onSignedIn = {
                                    signedIn = true
                                    app.sync.syncNow()
                                    scope.launch { email = app.sync.currentEmail() }
                                }
                            )
                        }
                        // Open settings take the whole screen: sharing it with the board made a
                        // short inner scroll area that hid its lower half (wallpaper, sign-out).
                        if (settingsOpen) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                SetupScreen(
                                    overlayOk = overlayOk,
                                    batteryOk = batteryOk,
                                    notifyOk = notifyOk,
                                    onOverlay = { openOverlaySettings() },
                                    onBattery = { openBatterySettings() },
                                    onNotify = { openNotificationSettings() },
                                    onStart = { onStartServiceClicked() },
                                    updateStatus = updateStatus,
                                    updateBusy = updateBusy,
                                    canInstallUpdate = pendingUpdate != null,
                                    onCheckUpdate = checkForUpdate,
                                    onInstallUpdate = installUpdate
                                )
                                AppearanceSection(
                                    themeMode = themeMode,
                                    onTheme = { app.settings.setThemeMode(it) },
                                    onPickWallpaper = { wallpaperPicker.launch("image/*") },
                                    onClearWallpaper = {
                                        app.settings.wallpaperFile().delete()
                                        app.settings.markWallpaperChanged()
                                    }
                                )
                                if (signedIn == true) {
                                    AccountSection(
                                        email = email,
                                        liveStatus = liveStatus,
                                        healthLine = syncHealthLine(health.lastOkAt, health.pending, now),
                                        error = health.lastError?.takeIf { it.isNotBlank() },
                                        onSyncNow = { app.sync.syncNow() },
                                        onSignOut = {
                                            scope.launch {
                                                app.sync.signOut()
                                                signedIn = false
                                                email = null
                                            }
                                        }
                                    )
                                }
                                if (signedIn == false) login()
                            }
                        } else {
                            Spacer(Modifier.height(8.dp))
                            when (signedIn) {
                                null -> Unit
                                false -> login()
                                true -> EditorScreen(
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
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
        val app = application as LockListApp
        app.sync.startRealtime()
        app.sync.requestQuickSync("app opened")
        app.catchUpDailyReset()
        // After a self-update HyperOS may refuse to auto-restart the service; opening the
        // app is enough to bring the lock screen back once the permissions are in place.
        if (overlayOk.value && notifyOk.value) startScreenService()
    }

    private fun knownUpdateCode(): Int =
        getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE).getInt(KEY_KNOWN_UPDATE, 0)

    private fun saveKnownUpdateCode(code: Int) {
        getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE).edit().putInt(KEY_KNOWN_UPDATE, code).apply()
    }

    private fun isDarkTheme(mode: String): Boolean = when (mode) {
        AppSettings.THEME_DARK -> true
        AppSettings.THEME_LIGHT -> false
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun applySystemBars(dark: Boolean) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) {
            SystemBarStyle.dark(transparent)
        } else {
            SystemBarStyle.light(transparent, transparent)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    /**
     * Runs on an IO thread. Shrinks the photo to the screen size, writes it to a temp file,
     * then swaps it over the old wallpaper, so a failure part-way never leaves a broken one.
     */
    private fun saveWallpaper(uri: Uri): Boolean {
        val app = application as LockListApp
        val dest = app.settings.wallpaperFile()
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        return try {
            val metrics = resources.displayMetrics
            val screenW = minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
            val screenH = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
            val source = ImageDecoder.createSource(contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width.coerceAtLeast(1)
                val h = info.size.height.coerceAtLeast(1)
                // Just big enough to cover the screen when cropped to fill it.
                val scale = maxOf(screenW / w.toFloat(), screenH / h.toFloat())
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (w * scale).roundToInt().coerceAtLeast(1),
                        (h * scale).roundToInt().coerceAtLeast(1)
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            FileOutputStream(tmp).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) { "could not encode wallpaper" }
            }
            bitmap.recycle()
            check(tmp.renameTo(dest)) { "could not replace wallpaper" }
            app.settings.markWallpaperChanged()
            true
        } catch (e: Exception) {
            Log.w("LockList", "could not set wallpaper", e)
            tmp.delete()
            false
        } catch (e: OutOfMemoryError) {
            Log.w("LockList", "wallpaper too large", e)
            tmp.delete()
            false
        }
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

private const val UPDATE_PREFS = "locklist_update"
private const val KEY_KNOWN_UPDATE = "knownUpdateCode"

/** Tap to fold setup, appearance and account away; closed, it is one summary line. */
@Composable
private fun SettingsHeader(open: Boolean, summary: AnnotatedString, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = if (open) "Hide settings" else "Show settings", onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Settings", style = MaterialTheme.typography.titleMedium)
            if (!open) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        ExpandChevron(
            expanded = open,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
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
    onStart: () -> Unit,
    updateStatus: String,
    updateBusy: Boolean,
    canInstallUpdate: Boolean,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Setup", style = MaterialTheme.typography.titleMedium)
        PermissionRow("Display over other apps", overlayOk.value, onOverlay)
        PermissionRow("Battery unrestricted", batteryOk.value, onBattery)
        PermissionRow("Notifications", notifyOk.value, onNotify)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("Start service")
        }
        Text(updateStatus, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCheckUpdate, enabled = !updateBusy) {
                Text("Check for update")
            }
            if (canInstallUpdate) {
                Button(onClick = onInstallUpdate, enabled = !updateBusy) {
                    Text("Install")
                }
            }
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

/** Who is signed in, whether the socket is up, and whether changes actually reach the web. */
@Composable
private fun AccountSection(
    email: String?,
    liveStatus: String,
    healthLine: String,
    error: String?,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Account", style = MaterialTheme.typography.titleMedium)
        Text(
            "Signed in as ${email ?: "portal admin"}",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            liveStatus,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            healthLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = warningColor()
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onSyncNow) { Text("Sync now") }
            TextButton(onClick = onSignOut) { Text("Sign out") }
        }
    }
}
