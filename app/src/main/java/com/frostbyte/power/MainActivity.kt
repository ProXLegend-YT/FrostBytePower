package com.frostbyte.power

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.frostbyte.power.ui.theme.FrostBytePowerTheme

private enum class Screen { DISCLOSURE, ONBOARDING, SETTINGS }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FrostBytePowerTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    FrostByteRoot()
                }
            }
        }
    }
}

@Composable
fun FrostByteRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var serviceEnabled by remember { mutableStateOf(AccessibilityUtils.isServiceEnabled(context)) }
    var disclosureAcknowledged by remember { mutableStateOf(PowerPrefs.hasSeenOnboarding(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceEnabled = AccessibilityUtils.isServiceEnabled(context)
                PowerButtonService.instance?.applyAllLiveSettings()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val screen = when {
        !disclosureAcknowledged -> Screen.DISCLOSURE
        !serviceEnabled -> Screen.ONBOARDING
        else -> Screen.SETTINGS
    }

    when (screen) {
        Screen.DISCLOSURE -> DisclosureScreen(
            onContinue = {
                PowerPrefs.setSeenOnboarding(context)
                disclosureAcknowledged = true
            }
        )
        Screen.ONBOARDING -> OnboardingScreen(
            onEnableClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )
        Screen.SETTINGS -> SettingsScreen()
    }
}

@Composable
fun OnboardingScreen(onEnableClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "One Last Step",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Turn on the FrostByte Power accessibility service to activate your button and gesture controls.",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 21.sp
        )

        Spacer(modifier = Modifier.height(36.dp))

        Button(
            onClick = onEnableClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(text = "Enable Accessibility Access", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Tap above, then find \"FrostByte Power\" in the list and turn it on.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SettingsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current

    var volUpAction by remember { mutableStateOf(PowerPrefs.getVolumeUpAction(context)) }
    var volDownAction by remember { mutableStateOf(PowerPrefs.getVolumeDownAction(context)) }
    var volUpDoubleAction by remember { mutableStateOf(PowerPrefs.getVolumeUpDoubleTapAction(context)) }
    var volDownDoubleAction by remember { mutableStateOf(PowerPrefs.getVolumeDownDoubleTapAction(context)) }
    var volUpLongAction by remember { mutableStateOf(PowerPrefs.getVolumeUpLongPressAction(context)) }
    var volDownLongAction by remember { mutableStateOf(PowerPrefs.getVolumeDownLongPressAction(context)) }
    var powerAction by remember { mutableStateOf(PowerPrefs.getPowerButtonAction(context)) }
    var shakeSensitivity by remember { mutableStateOf(PowerPrefs.getShakeSensitivity(context)) }
    var soundProfile by remember { mutableStateOf(QuickActions.getCurrentSoundProfile(context)) }
    var lowPowerMode by remember { mutableStateOf(PowerPrefs.isLowPowerMode(context)) }
    var batteryCutoffEnabled by remember { mutableStateOf(PowerPrefs.isShakeBatteryCutoffEnabled(context)) }
    var vibrationFeedback by remember { mutableStateOf(PowerPrefs.isVibrationFeedbackEnabled(context)) }
    var screenTimeout by remember { mutableStateOf(PowerPrefs.getScreenTimeout(context)) }
    var rotationLocked by remember { mutableStateOf(DeviceUtils.isRotationLocked(context)) }
    var dndOn by remember { mutableStateOf(QuickActions.isDndOn(context)) }
    var batteryPercent by remember { mutableStateOf(DeviceUtils.getBatteryPercent(context)) }
    var ignoreProximity by remember { mutableStateOf(PowerPrefs.isIgnoreProximityEnabled(context)) }
    var importResultMessage by remember { mutableStateOf<String?>(null) }

    fun pingService() {
        PowerButtonService.instance?.applyAllLiveSettings()
    }

    val phoneStatePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ignoreProximity = true
            PowerPrefs.setIgnoreProximityEnabled(context, true)
            pingService()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { SettingsBackup.writeToUri(context, it) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val success = SettingsBackup.readFromUri(context, it)
            importResultMessage = if (success) "Settings imported" else "Import failed — invalid file"
            if (success) {
                volUpAction = PowerPrefs.getVolumeUpAction(context)
                volDownAction = PowerPrefs.getVolumeDownAction(context)
                volUpDoubleAction = PowerPrefs.getVolumeUpDoubleTapAction(context)
                volDownDoubleAction = PowerPrefs.getVolumeDownDoubleTapAction(context)
                volUpLongAction = PowerPrefs.getVolumeUpLongPressAction(context)
                volDownLongAction = PowerPrefs.getVolumeDownLongPressAction(context)
                powerAction = PowerPrefs.getPowerButtonAction(context)
                shakeSensitivity = PowerPrefs.getShakeSensitivity(context)
                lowPowerMode = PowerPrefs.isLowPowerMode(context)
                batteryCutoffEnabled = PowerPrefs.isShakeBatteryCutoffEnabled(context)
                vibrationFeedback = PowerPrefs.isVibrationFeedbackEnabled(context)
                screenTimeout = PowerPrefs.getScreenTimeout(context)
                ignoreProximity = PowerPrefs.isIgnoreProximityEnabled(context)
                pingService()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp, bottom = 32.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "FrostByte Power",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = "${batteryPercent}%",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3DDC97))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Service active",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        SectionLabel("Volume + (single / double / long-press)")

        ActionSelectorCard(
            title = "Single tap",
            options = ButtonAction.entries,
            selectedLabel = volUpAction.label,
            onSelected = { volUpAction = it; PowerPrefs.setVolumeUpAction(context, it) }
        )
        Spacer(modifier = Modifier.height(10.dp))
        ActionSelectorCard(
            title = "Double tap",
            options = ButtonAction.entries,
            selectedLabel = volUpDoubleAction.label,
            onSelected = { volUpDoubleAction = it; PowerPrefs.setVolumeUpDoubleTapAction(context, it) }
        )
        Spacer(modifier = Modifier.height(10.dp))
        ActionSelectorCard(
            title = "Long press",
            options = ButtonAction.entries,
            selectedLabel = volUpLongAction.label,
            onSelected = { volUpLongAction = it; PowerPrefs.setVolumeUpLongPressAction(context, it) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("Volume − (single / double / long-press)")

        ActionSelectorCard(
            title = "Single tap",
            options = ButtonAction.entries,
            selectedLabel = volDownAction.label,
            onSelected = { volDownAction = it; PowerPrefs.setVolumeDownAction(context, it) }
        )
        Spacer(modifier = Modifier.height(10.dp))
        ActionSelectorCard(
            title = "Double tap",
            options = ButtonAction.entries,
            selectedLabel = volDownDoubleAction.label,
            onSelected = { volDownDoubleAction = it; PowerPrefs.setVolumeDownDoubleTapAction(context, it) }
        )
        Spacer(modifier = Modifier.height(10.dp))
        ActionSelectorCard(
            title = "Long press",
            options = ButtonAction.entries,
            selectedLabel = volDownLongAction.label,
            onSelected = { volDownLongAction = it; PowerPrefs.setVolumeDownLongPressAction(context, it) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("Power button")

        ActionSelectorCard(
            title = "Power button action",
            options = listOf(ButtonAction.DEFAULT, ButtonAction.DISABLED),
            selectedLabel = powerAction.label,
            onSelected = { powerAction = it; PowerPrefs.setPowerButtonAction(context, it) },
            helperText = "Android reserves the physical power key system-wide, so this only has effect on devices that route it through accessibility."
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("Motion")

        ActionSelectorCard(
            title = "Shake to Wake",
            options = ShakeSensitivity.entries,
            selectedLabel = shakeSensitivity.label,
            onSelected = { shakeSensitivity = it; PowerPrefs.setShakeSensitivity(context, it); pingService() }
        )

        Spacer(modifier = Modifier.height(10.dp))

        ToggleRow(
            label = "Auto-disable shake below ${PowerPrefs.getShakeBatteryCutoffPercent(context)}% battery",
            checked = batteryCutoffEnabled,
            onCheckedChange = {
                batteryCutoffEnabled = it
                PowerPrefs.setShakeBatteryCutoffEnabled(context, it)
                pingService()
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("Proximity sensor workaround")

        ToggleRow(
            label = "Force speakerphone on calls (fixes stuck-screen calls)",
            checked = ignoreProximity,
            onCheckedChange = { checked ->
                if (checked) {
                    val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.READ_PHONE_STATE
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (hasPermission) {
                        ignoreProximity = true
                        PowerPrefs.setIgnoreProximityEnabled(context, true)
                        pingService()
                    } else {
                        phoneStatePermissionLauncher.launch(android.Manifest.permission.READ_PHONE_STATE)
                    }
                } else {
                    ignoreProximity = false
                    PowerPrefs.setIgnoreProximityEnabled(context, false)
                    pingService()
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Automatically switches phone calls and WhatsApp calls to speaker the moment they connect, since the proximity-controlled screen-off only happens in earpiece mode. This does not cover WhatsApp voice MESSAGE playback — that audio route is controlled entirely inside WhatsApp and can't be forced from outside it.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 15.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("Sensors Off tile shortcut")

        var tapPosition by remember { mutableStateOf(PowerPrefs.getSensorsOffTapPosition(context)) }

        Text(
            text = if (tapPosition != null)
                "Calibrated at (${tapPosition!!.first}, ${tapPosition!!.second}). Assign \"Tap Sensors Off Tile\" to a button below to use it."
            else
                "Not calibrated yet. This lets a button open quick settings and automatically tap your Sensors Off tile, since Android gives apps no way to find that tile by name.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 15.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {
                PowerButtonService.instance?.startSensorsOffCalibration(
                    onCaptured = { x, y ->
                        PowerPrefs.setSensorsOffTapPosition(context, x, y)
                        tapPosition = x to y
                    },
                    onCancelled = {
                        android.widget.Toast.makeText(
                            context,
                            "Couldn't start calibration — is the accessibility service enabled?",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }) {
                Text(if (tapPosition != null) "Re-calibrate" else "Calibrate")
            }
            if (tapPosition != null) {
                OutlinedButton(onClick = {
                    PowerPrefs.clearSensorsOffTapPosition(context)
                    tapPosition = null
                }) {
                    Text("Clear")
                }
            }
        }

        Text(
            text = "Re-calibrate if the tile ever moves — a new notification, reordered tiles, or rotating the screen can shift its position.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("Power saving")

        ToggleRow(
            label = "Low-power mode (reduces motion sensor rate)",
            checked = lowPowerMode,
            onCheckedChange = {
                lowPowerMode = it
                PowerPrefs.setLowPowerMode(context, it)
                pingService()
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        ToggleRow(
            label = "Vibration feedback on actions",
            checked = vibrationFeedback,
            onCheckedChange = {
                vibrationFeedback = it
                PowerPrefs.setVibrationFeedbackEnabled(context, it)
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("Display")

        ActionSelectorCard(
            title = "Screen timeout",
            options = ScreenTimeout.entries,
            selectedLabel = screenTimeout.label,
            onSelected = {
                screenTimeout = it
                PowerPrefs.setScreenTimeout(context, it)
                if (DeviceUtils.canWriteSystemSettings(context)) {
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_OFF_TIMEOUT,
                        it.millis
                    )
                } else {
                    DeviceUtils.requestWriteSystemSettingsPermission(context)
                }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("Quick actions")

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            QuickActionTile(
                icon = Icons.Filled.LockOpen,
                modifier = Modifier.weight(1f),
                onClick = { QuickActions.lockScreen() }
            )
            QuickActionTile(
                icon = Icons.Filled.PowerSettingsNew,
                modifier = Modifier.weight(1f),
                onClick = { QuickActions.openPowerMenu() }
            )
            QuickActionTile(
                icon = soundProfileIcon(soundProfile),
                modifier = Modifier.weight(1f),
                onClick = {
                    if (QuickActions.canChangeSoundProfile(context)) {
                        soundProfile = QuickActions.cycleSoundProfile(context)
                    } else {
                        QuickActions.requestSoundProfilePermission(context)
                    }
                }
            )
            QuickActionTile(
                icon = Icons.Filled.FlashOn,
                modifier = Modifier.weight(1f),
                onClick = { QuickActions.toggleFlashlight(context) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            QuickActionTile(
                icon = if (rotationLocked) Icons.Filled.ScreenLockRotation else Icons.Filled.ScreenRotation,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (DeviceUtils.canWriteSystemSettings(context)) {
                        QuickActions.toggleRotationLock(context)
                        rotationLocked = DeviceUtils.isRotationLocked(context)
                    } else {
                        DeviceUtils.requestWriteSystemSettingsPermission(context)
                    }
                }
            )
            QuickActionTile(
                icon = if (dndOn) Icons.Filled.DoNotDisturbOn else Icons.Filled.DoNotDisturbOff,
                modifier = Modifier.weight(1f),
                onClick = {
                    QuickActions.toggleDnd(context)
                    dndOn = QuickActions.isDndOn(context)
                }
            )
            QuickActionTile(
                icon = Icons.Filled.CameraAlt,
                modifier = Modifier.weight(1f),
                onClick = { PowerButtonService.instance?.let { DeviceUtils.takeScreenshot(it) } }
            )
            Spacer(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Sound: ${soundProfile.label}" +
                if (!QuickActions.canChangeSoundProfile(context)) " (tap speaker icon to grant DND access)" else "",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("Backup")

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { exportLauncher.launch("frostbyte-power-settings.json") },
                modifier = Modifier.weight(1f)
            ) {
                Text("Export settings")
            }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json")) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Import settings")
            }
        }

        importResultMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Changes apply immediately — no restart needed.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

fun soundProfileIcon(profile: SoundProfile): ImageVector = when (profile) {
    SoundProfile.RING -> Icons.Filled.VolumeUp
    SoundProfile.VIBRATE -> Icons.Filled.Vibration
    SoundProfile.SILENT -> Icons.Filled.VolumeOff
}

@Composable
fun QuickActionTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ActionSelectorCard(
    title: String,
    options: List<T>,
    selectedLabel: String,
    onSelected: (T) -> Unit,
    helperText: String? = null
) where T : Enum<T> {
    var expanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))

            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .clickable { expanded = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedLabel,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                ) {
                    options.forEach { option ->
                        val label = when (option) {
                            is ButtonAction -> option.label
                            is ShakeSensitivity -> option.label
                            is ScreenTimeout -> option.label
                            else -> option.toString()
                        }
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                onSelected(option)
                                expanded = false
                            }
                        )
                    }
                }
            }

            if (helperText != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = helperText,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp
                )
            }
        }
    }
}
