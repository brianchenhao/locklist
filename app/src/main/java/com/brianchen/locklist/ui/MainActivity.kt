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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.brianchen.locklist.service.ScreenService
import com.brianchen.locklist.ui.theme.LockListTheme

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LockListTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SetupScreen(
                        overlayOk = overlayOk,
                        batteryOk = batteryOk,
                        notifyOk = notifyOk,
                        onOverlay = { openOverlaySettings() },
                        onBattery = { openBatterySettings() },
                        onNotify = { openNotificationSettings() },
                        onStart = { onStartServiceClicked() },
                        modifier = Modifier.padding(innerPadding)
                    )
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
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
