package dev.afterbeep.ui.screens

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.afterbeep.BuildConfig
import dev.afterbeep.ui.MainViewModel
import dev.afterbeep.ui.components.BeepButton
import dev.afterbeep.ui.components.Card
import dev.afterbeep.ui.components.GhostButton
import dev.afterbeep.ui.components.SectionLabel
import dev.afterbeep.ui.components.StatusPill
import dev.afterbeep.ui.theme.Ab

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val status by vm.engineStatus.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.lastError.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val contactsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.updateSettings { it.copy(starredRingThrough = true) }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, color = Ab.Text)

        // ---- engine
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Engine", style = MaterialTheme.typography.titleLarge, color = Ab.Text)
                    Text(
                        if (status.running) "Answering calls · pid ${status.pid}" else "Not running — calls ring normally",
                        style = MaterialTheme.typography.bodySmall, color = Ab.Muted,
                    )
                }
                StatusPill(if (status.running) "On" else "Off", if (status.running) Ab.Mint else Ab.Red, status.running)
            }
            Spacer(Modifier.height(14.dp))
            if (busy != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ab.Orange)
                    Spacer(Modifier.width(10.dp))
                    Text(busy!!, style = MaterialTheme.typography.bodyMedium, color = Ab.Text)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BeepButton(if (status.running) "Restart" else "Start engine") {
                        val reason = vm.engine.diagnose()
                        if (reason != null && s.paired && !reason.startsWith("Connect to Wi-Fi")) {
                            vm.engine.startWhenReady()
                            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } else if (!s.paired) {
                            vm.updateSettings { it.copy(onboarded = false) }
                        } else vm.engine.startWhenReady()
                    }
                    if (status.running) GhostButton("Stop", color = Ab.Red) { vm.engine.stopEngine() }
                    GhostButton("Wireless debugging") {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Ab.Red)
            }
            val usb by vm.engine.usbDebug.collectAsStateWithLifecycle()
            if (s.paired && !usb) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "USB debugging is off — Afterbeep will stop as soon as you leave Wi-Fi. Turn it on in Developer options (no cable needed).",
                    style = MaterialTheme.typography.bodySmall, color = Ab.Amber,
                )
                Spacer(Modifier.height(8.dp))
                GhostButton("Turn on USB debugging") {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            if (!s.paired) {
                Spacer(Modifier.height(10.dp))
                GhostButton("Pair with Wireless debugging") { vm.updateSettings { it.copy(onboarded = false) } }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Starting needs Wi-Fi and Wireless debugging for a moment. With USB debugging on, it then keeps running off Wi-Fi until the next reboot.",
                style = MaterialTheme.typography.bodySmall, color = Ab.Faint,
            )
        }

        // ---- answering
        SectionLabel("Answering")
        Card(padding = 6.dp) {
            ToggleRow("Take messages", "Answer calls I don't pick up", s.enabled) { v -> vm.updateSettings { it.copy(enabled = v) } }
            Divider()
            SliderRow("Answer after", "${s.ringSeconds} s of ringing", s.ringSeconds.toFloat(), 3f..40f) { v ->
                vm.updateSettings { it.copy(ringSeconds = v.toInt()) }
            }
            Divider()
            SliderRow("Longest message", "${s.maxSeconds / 60}:%02d".format(s.maxSeconds % 60), s.maxSeconds.toFloat(), 30f..300f) { v ->
                vm.updateSettings { it.copy(maxSeconds = (v / 10).toInt() * 10) }
            }
        }

        SectionLabel("Privacy")
        Card(padding = 6.dp) {
            ToggleRow("Silent on this phone", "Caller is never played out of my earpiece or speaker", s.silenceLocal) { v ->
                vm.updateSettings { it.copy(silenceLocal = v) }
            }
            Divider()
            ToggleRow("Favourites ring through", "Starred contacts always ring — never sent to voicemail", s.starredRingThrough) { v ->
                if (v && ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    contactsPerm.launch(Manifest.permission.READ_CONTACTS)
                } else vm.updateSettings { it.copy(starredRingThrough = v) }
            }
            Divider()
            ToggleRow("Repeat callers ring through", "If the same number calls again within 3 minutes, it rings — it's probably urgent", s.repeatRingThrough) { v ->
                vm.updateSettings { it.copy(repeatRingThrough = v) }
            }
        }

        SectionLabel("Alerts")
        Card(padding = 6.dp) {
            ToggleRow(
                "Status notification", "Always shows whether voicemail is On or Off — no surprises",
                s.statusNotification,
            ) { v ->
                vm.updateSettings { it.copy(statusNotification = v) }
                if (v) dev.afterbeep.engine.StatusService.start(ctx) else dev.afterbeep.engine.StatusService.stop(ctx)
            }
        }
        Card {
            Text("Quick Settings tile", style = MaterialTheme.typography.titleMedium, color = Ab.Text)
            Text(
                "A “Voicemail · On/Off” tile at the very top of the pull-down — always visible, above every notification.",
                style = MaterialTheme.typography.bodySmall, color = Ab.Muted,
            )
            Spacer(Modifier.height(12.dp))
            GhostButton("Add to Quick Settings") {
                ctx.getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                    android.content.ComponentName(ctx, dev.afterbeep.engine.VoicemailTile::class.java),
                    "Voicemail",
                    android.graphics.drawable.Icon.createWithResource(ctx, dev.afterbeep.R.drawable.ic_stat_beep),
                    ctx.mainExecutor,
                ) { }
            }
        }
        Card {
            Text("New voicemail notifications", style = MaterialTheme.typography.titleMedium, color = Ab.Text)
            Text(
                "OnePlus hides pop-up banners for new apps. Open settings and turn on Banners, Lock screen and Sound so every message pings you.",
                style = MaterialTheme.typography.bodySmall, color = Ab.Muted,
            )
            Spacer(Modifier.height(12.dp))
            GhostButton("Notification settings") {
                ctx.startActivity(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, dev.afterbeep.AfterbeepApp.CH_MESSAGES)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        SectionLabel("Security")
        Card(padding = 6.dp) {
            ToggleRow(
                "Lock debugging to trusted devices",
                "Rejects every “Allow debugging?” prompt instantly — strangers on Wi-Fi or a USB cable can't get in, even if you tap Allow by mistake. Add your own computer with Wireless debugging → Pair with code.",
                s.lockDebugging,
            ) { v -> vm.updateSettings { it.copy(lockDebugging = v) } }
        }

        SectionLabel("After a reboot")
        Card(padding = 6.dp) {
            ToggleRow(
                "Restart automatically",
                if (vm.engine.hasSecureSettings) "Flips Wireless debugging on for a few seconds on Wi-Fi, then off"
                else "Needs a one-time permission — start the engine once and it's granted",
                s.autoStart && vm.engine.hasSecureSettings, enabled = vm.engine.hasSecureSettings,
            ) { v -> vm.updateSettings { it.copy(autoStart = v) } }
            val blocked by vm.engine.grantBlocked.collectAsStateWithLifecycle()
            if (blocked && !vm.engine.hasSecureSettings) {
                Text(
                    "Your phone blocked the permission. Turn on Developer options → “Disable permission monitoring”, then tap Restart above.",
                    style = MaterialTheme.typography.bodySmall, color = Ab.Amber,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Afterbeep ${BuildConfig.VERSION_NAME} · everything stays on this phone",
            style = MaterialTheme.typography.bodySmall, color = Ab.Faint, modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = Ab.Outline.copy(alpha = 0.6f))

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) Ab.Text else Ab.Faint)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ab.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked, onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ab.Ink, checkedTrackColor = Ab.Orange, checkedBorderColor = Ab.Orange,
                uncheckedThumbColor = Ab.Muted, uncheckedTrackColor = Ab.Surface3, uncheckedBorderColor = Ab.Outline,
            ),
        )
    }
}

@Composable
private fun SliderRow(title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onDone: (Float) -> Unit) {
    var v by remember(current) { mutableFloatStateOf(current) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ab.Text, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = Ab.Orange)
        }
        Slider(
            value = v, onValueChange = { v = it; onDone(it) }, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Ab.Amber, activeTrackColor = Ab.Orange, inactiveTrackColor = Ab.Surface3),
        )
    }
}
