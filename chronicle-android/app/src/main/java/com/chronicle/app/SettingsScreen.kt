package com.chronicle.app

import android.Manifest
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.chronicle.app.ai.AiAvailability
import com.chronicle.app.ai.AiFeature
import com.chronicle.app.ai.NanoModelPreference
import com.chronicle.app.ai.NanoReleaseStage
import com.chronicle.app.e2ee.E2eeManager
import com.chronicle.app.health.HealthConnectAvailability
import com.chronicle.app.health.HealthConnectManager
import com.chronicle.app.net.LanOutboxWorker
import com.chronicle.app.net.NsdHelper
import com.chronicle.app.net.ServeClient
import com.chronicle.app.ui.theme.ChronicleChrome
import com.chronicle.app.ui.theme.ThemeMode
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()
    val reminderEnabled by viewModel.reminderEnabled.collectAsState()
    val reminderHour by viewModel.reminderHour.collectAsState()
    val reminderMinute by viewModel.reminderMinute.collectAsState()
    val dynamicColor by viewModel.dynamicColorEnabled.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val folderUri by viewModel.folderUri.collectAsState()
    val serveBaseUrl by viewModel.serveBaseUrl.collectAsState()
    val extraCidrs by viewModel.extraCidrs.collectAsState()
    val lanHealthOk by viewModel.lanHealthOk.collectAsState()
    val brainFreshness by viewModel.brainFreshness.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val kbNotes by viewModel.kbNotes.collectAsState()
    val notes by viewModel.notes.collectAsState()
    var showTimePicker by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var serveDraft by remember(serveBaseUrl) { mutableStateOf(serveBaseUrl) }
    var cidrDraft by remember { mutableStateOf("") }
    val connectedBase = serveBaseUrl
    val lanStatus = lanHealthUi(connectedBase.isNotBlank(), lanHealthOk)
    val vaultSubtitle = vaultStatusSubtitle(
        folderPicked = folderUri != null,
        brainFreshness = brainFreshness,
        entryCount = entries.size,
        noteCount = kbNotes.size + notes.size,
    )

    LaunchedEffect(connectedBase) {
        viewModel.checkLanHealth(context)
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.setFolderUri(context, uri)
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* optional */ }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) showQrScanner = true
    }

    fun applyLanBaseUrl(raw: String, token: String? = null, tlsFp: String? = null) {
        val normalized = ServeClient.normalizeBaseUrl(raw)
        if (normalized.isNotBlank() && !ServeClient.isAllowedLanUrl(normalized, extraCidrs)) {
            return
        }
        viewModel.setServeBaseUrl(context, normalized, token = token, tlsFp = tlsFp)
        serveDraft = normalized
        viewModel.checkLanHealth(context)
    }

    fun clearLanBaseUrls() {
        viewModel.setServeBaseUrl(context, "", token = "")
        serveDraft = ""
        viewModel.checkLanHealth(context)
    }

    fun startMacQrScan() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            showQrScanner = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Column(modifier = Modifier.fillMaxSize()) {
        LargeTopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(
                    onClick = { viewModel.navigateBack() },
                    modifier = Modifier.testTag("settings_back"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = ChronicleChrome.topBarContainer(),
                scrolledContainerColor = ChronicleChrome.topBarScrolled(),
            ),
            modifier = Modifier.statusBarsPadding(),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsSection(title = "Appearance") {
                    SettingsToggleRow(
                        title = "Material You",
                        subtitle = if (Build.VERSION.SDK_INT >= 31) {
                            "Colors from your wallpaper"
                        } else {
                            "Requires Android 12+"
                        },
                        checked = dynamicColor,
                        enabled = Build.VERSION.SDK_INT >= 31,
                        testTag = "settings_dynamic_color_toggle",
                        onCheckedChange = { viewModel.setDynamicColorEnabled(context, it) },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Theme",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    viewModel.setThemeMode(context, mode)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                modifier = Modifier.testTag("settings_theme_${mode.name.lowercase()}"),
                            ) {
                                Text(
                                    when (mode) {
                                        ThemeMode.SYSTEM -> "System"
                                        ThemeMode.LIGHT -> "Light"
                                        ThemeMode.DARK -> "Dark"
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "Security") {
                    SettingsToggleRow(
                        title = "App lock",
                        subtitle = "Biometric unlock",
                        checked = biometricEnabled,
                        testTag = "settings_biometric_toggle",
                        onCheckedChange = { viewModel.setBiometricEnabled(context, it) },
                    )
                }
            }

            item {
                SettingsSection(title = "Reminders") {
                    SettingsToggleRow(
                        title = "Daily reminder",
                        subtitle = "Off by default",
                        checked = reminderEnabled,
                        testTag = "settings_reminder_toggle",
                        onCheckedChange = { enabled ->
                            if (enabled && Build.VERSION.SDK_INT >= 33) {
                                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            viewModel.setReminderEnabled(context, enabled)
                        },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showTimePicker = true }
                            .padding(vertical = 8.dp)
                            .testTag("settings_reminder_time"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Reminder time", fontWeight = FontWeight.Medium)
                        Text(
                            "%02d:%02d".format(reminderHour, reminderMinute),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            item {
                SettingsSection(title = "Vault") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Current folder", fontWeight = FontWeight.SemiBold)
                            Text(
                                folderUri?.let { Uri.parse(it).lastPathSegment } ?: "Not set",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("settings_vault_path"),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        vaultSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("settings_vault_status"),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Journal updates sync via Syncthing, not this LAN link.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        modifier = Modifier.testTag("settings_vault_sync_note"),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            val start = folderUri?.let { Uri.parse(it) }
                            folderLauncher.launch(start)
                        },
                        modifier = Modifier.testTag("settings_change_folder"),
                    ) {
                        Text("Change folder")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                viewModel.navigateTo(Screen.PORTFOLIO)
                            }
                            .padding(vertical = 8.dp)
                            .testTag("settings_open_portfolio"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Work,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Open Portfolio", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Resume points from 10-Work/ResumePoints",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                HealthDataSettingsSection(viewModel = viewModel)
            }

            item {
                SettingsSection(title = "Mac on LAN (Brain / Recall)") {
                    Text(
                        "Trusted local network only — no cloud. Scan the QR from chronicle serve --lan to enable graph-seeded Recall in Brain. Full vault RAG prefers this Mac link. This does not sync your journal files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { startMacQrScan() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_scan_mac_qr"),
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan Mac QR")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        lanHealthLabel(lanStatus),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = when (lanStatus) {
                            LanHealthUi.MAC_REACHABLE -> MaterialTheme.colorScheme.primary
                            LanHealthUi.MAC_UNREACHABLE -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.testTag("settings_lan_status"),
                    )
                    if (connectedBase.isNotBlank()) {
                        Text(
                            connectedBase,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("settings_lan_connected_url"),
                        )
                        TextButton(
                            onClick = { clearLanBaseUrls() },
                            modifier = Modifier.testTag("settings_lan_clear"),
                        ) {
                            Text("Clear")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { advancedExpanded = !advancedExpanded }
                            .padding(vertical = 8.dp)
                            .testTag("settings_lan_advanced_toggle"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Advanced",
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (advancedExpanded) "Collapse" else "Expand",
                        )
                    }
                    if (advancedExpanded) {
                        Text(
                            "Manual override for power users. Prefer Scan Mac QR when using the unified gateway.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = serveDraft,
                            onValueChange = { serveDraft = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_serve_url"),
                            label = { Text("Chronicle serve URL") },
                            placeholder = { Text("https://192.168.1.10:8765") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                        )
                        TextButton(
                            onClick = {
                                applyLanBaseUrl(serveDraft)
                            },
                            modifier = Modifier.testTag("settings_serve_save"),
                        ) { Text("Save serve URL") }

                        Spacer(modifier = Modifier.height(4.dp))
                        val scope = rememberCoroutineScope()
                        var discovering by remember { mutableStateOf(false) }
                        Button(
                            onClick = {
                                discovering = true
                                scope.launch {
                                    // First resolved instance wins; bounded wait.
                                    val found = withTimeoutOrNull(6_000) {
                                        NsdHelper.discover(context).first()
                                    }
                                    if (found != null) {
                                        val scheme = if (found.tls) "https" else "http"
                                        applyLanBaseUrl(
                                            "$scheme://${found.host}:${found.port}",
                                            tlsFp = found.tlsFp.takeIf { found.tls },
                                        )
                                    }
                                    discovering = false
                                }
                            },
                            enabled = !discovering,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_lan_discover"),
                        ) {
                            Text(if (discovering) "Searching…" else "Discover Mac on network")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = cidrDraft,
                            onValueChange = { cidrDraft = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_extra_cidrs"),
                            label = { Text("Extra allowed CIDRs (VPN, optional)") },
                            placeholder = { Text("100.64.0.0/10") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            shape = RoundedCornerShape(12.dp),
                        )
                        TextButton(
                            onClick = {
                                if (viewModel.setExtraCidrs(context, cidrDraft)) {
                                    viewModel.checkLanHealth(context)
                                }
                            },
                            modifier = Modifier.testTag("settings_cidrs_save"),
                        ) { Text("Save CIDRs") }
                        Spacer(modifier = Modifier.height(8.dp))
                        SettingsToggleRow(
                            title = "Instant LAN push",
                            subtitle = "Mirror new captures to the Mac over Wi-Fi immediately (Syncthing stays authoritative)",
                            checked = LanOutboxWorker.isEnabled(context),
                            testTag = "settings_outbox_toggle",
                            onCheckedChange = { enabled ->
                                LanOutboxWorker.setEnabled(context, enabled)
                            },
                        )
                    }
                }
            }

            item {
                LlmProviderSettingsSection(viewModel = viewModel)
            }

            item {
                E2eeSettingsSection(viewModel = viewModel)
            }

            item {
                OnDeviceAiSettingsSection(viewModel = viewModel)
            }

            item {
                SettingsSection(title = "About") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Version", fontWeight = FontWeight.Medium)
                        Text(
                            BuildConfig.VERSION_NAME,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("settings_version"),
                        )
                    }
                }
            }
        }
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = reminderHour,
            initialMinute = reminderMinute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setReminderTime(context, timeState.hour, timeState.minute)
                        showTimePicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
            title = { Text("Reminder time") },
            text = { TimePicker(state = timeState) },
        )
    }

    if (showQrScanner) {
        MacQrScannerDialog(
            onDismiss = { showQrScanner = false },
            onConnectScanned = { payload ->
                applyLanBaseUrl(
                    payload.baseUrl,
                    token = payload.token ?: "",
                    tlsFp = payload.tlsFp,
                )
                showQrScanner = false
            },
        )
    }
}

@Composable
private fun E2eeSettingsSection(viewModel: MainViewModel) {
    val context = LocalContext.current
    val enabled by viewModel.e2eeEnabled.collectAsState()
    val unlocked by viewModel.e2eeUnlocked.collectAsState()
    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    SettingsSection(title = "Encryption (E2EE)") {
        Text(
            if (!enabled) {
                "Encrypt new captures on this phone with a passphrase. The Mac needs the same passphrase to read them."
            } else if (unlocked) {
                "New captures are encrypted. Locked entries stay unreadable until you unlock with your passphrase."
            } else {
                "Encrypted captures are locked on this device right now."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_e2ee_passphrase"),
            label = {
                Text(
                    when {
                        !enabled -> "Set passphrase"
                        unlocked -> "Passphrase (already unlocked)"
                        else -> "Unlock passphrase"
                    },
                )
            },
            singleLine = true,
            visualTransformation =
                if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                IconButton(onClick = { showPassphrase = !showPassphrase }) {
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = if (showPassphrase) "Hide" else "Show",
                    )
                }
            },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                !enabled -> {
                    Button(
                        onClick = {
                            if (passphrase.length < 8) {
                                message = "Use at least 8 characters"
                            } else if (viewModel.e2eeEnable(context, passphrase)) {
                                message = "Encryption enabled"
                                passphrase = ""
                            }
                            Unit
                        },
                        modifier = Modifier.testTag("settings_e2ee_enable"),
                    ) { Text("Enable") }
                }
                unlocked -> {
                    Button(
                        onClick = {
                            viewModel.e2eeLock()
                            message = "Locked"
                        },
                        modifier = Modifier.testTag("settings_e2ee_lock"),
                    ) { Text("Lock now") }
                }
                else -> {
                    Button(
                        onClick = {
                            if (viewModel.e2eeUnlock(context, passphrase)) {
                                message = "Unlocked"
                                passphrase = ""
                            } else {
                                message = "Wrong passphrase"
                            }
                        },
                        enabled = passphrase.isNotBlank(),
                        modifier = Modifier.testTag("settings_e2ee_unlock"),
                    ) { Text("Unlock") }
                }
            }
        }
        message?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun HealthDataSettingsSection(viewModel: MainViewModel) {
    val context = LocalContext.current
    val availability by viewModel.healthAvailability.collectAsState()
    val permissionsGranted by viewModel.healthPermissionsGranted.collectAsState()
    val autoSync by viewModel.healthAutoSync.collectAsState()
    val lastImportMs by viewModel.healthLastImportMs.collectAsState()
    val importing by viewModel.healthImporting.collectAsState()
    val folderUri by viewModel.folderUri.collectAsState()
    var importMessage by remember { mutableStateOf<String?>(null) }

    val manager = remember { HealthConnectManager(context.applicationContext) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = manager.permissionContract(),
    ) { granted ->
        viewModel.onHealthPermissionsResult(context, granted)
    }

    LaunchedEffect(Unit) {
        viewModel.refreshHealthConnectStatus(context)
    }

    SettingsSection(title = "Health data") {
        Text(
            "Import sleep and steps from Health Connect into your vault. Data stays local.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        when (availability) {
            HealthConnectAvailability.UNAVAILABLE -> {
                Text(
                    "Health Connect is not available on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("settings_health_unavailable"),
                )
            }
            HealthConnectAvailability.NOT_INSTALLED,
            HealthConnectAvailability.UPDATE_REQUIRED,
            -> {
                Text(
                    if (availability == HealthConnectAvailability.UPDATE_REQUIRED) {
                        "Health Connect needs an update."
                    } else {
                        "Install Health Connect to import sleep and steps."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        try {
                            context.startActivity(manager.installOrUpdateIntent())
                        } catch (_: Exception) {
                            // ignore
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_health_install"),
                ) {
                    Text(
                        if (availability == HealthConnectAvailability.UPDATE_REQUIRED) {
                            "Update Health Connect"
                        } else {
                            "Install Health Connect"
                        },
                    )
                }
            }
            HealthConnectAvailability.AVAILABLE -> {
                if (!permissionsGranted) {
                    Button(
                        onClick = {
                            permissionLauncher.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_health_connect"),
                    ) {
                        Text("Connect Health Connect")
                    }
                } else {
                    Text(
                        "Connected — sleep & steps",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("settings_health_connected"),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            try {
                                context.startActivity(manager.openHealthConnectSettingsIntent())
                            } catch (_: Exception) {
                            }
                        },
                        modifier = Modifier.testTag("settings_health_manage"),
                    ) {
                        Text("Manage permissions")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        importMessage = null
                        viewModel.importHealthLast30Days(context) { ok ->
                            importMessage = if (ok) {
                                "Imported last 30 days"
                            } else {
                                "Import failed — check permissions and vault folder"
                            }
                        }
                    },
                    enabled = permissionsGranted && !importing && folderUri != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_health_import"),
                ) {
                    Text(if (importing) "Importing…" else "Import last 30 days")
                }
                if (importing) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_health_import_progress"),
                    )
                }
                importMessage?.let { msg ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("settings_health_import_message"),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                SettingsToggleRow(
                    title = "Auto-sync daily",
                    subtitle = "Import yesterday’s sleep and steps in the background",
                    checked = autoSync,
                    enabled = permissionsGranted && folderUri != null,
                    testTag = "settings_health_auto_sync",
                    onCheckedChange = { viewModel.setHealthAutoSync(context, it) },
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    lastImportMs?.let { ms ->
                        val formatted = DateFormat.getDateTimeInstance(
                            DateFormat.MEDIUM,
                            DateFormat.SHORT,
                        ).format(Date(ms))
                        "Last import: $formatted"
                    } ?: "Last import: never",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("settings_health_last_import"),
                )
            }
        }
    }
}

@Composable
private fun LlmProviderSettingsSection(viewModel: MainViewModel) {
    val context = LocalContext.current
    val provider by viewModel.llmProvider.collectAsState()
    val cloudConsent by viewModel.cloudConsent.collectAsState()
    val grokKey by viewModel.grokApiKey.collectAsState()
    val ollamaUrl by viewModel.ollamaLanUrl.collectAsState()
    var grokDraft by remember(grokKey) { mutableStateOf(grokKey) }
    var ollamaDraft by remember(ollamaUrl) { mutableStateOf(ollamaUrl) }
    var showKey by remember { mutableStateOf(false) }

    SettingsSection(title = "AI providers") {
        Text(
            "Local-first: Nano on-device, Ollama on LAN, or optional Grok (BYOK). Full vault RAG still prefers Mac when paired. No Vertex on Android.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        val providers = listOf(
            AndroidLlmProvider.NANO to "Nano",
            AndroidLlmProvider.OLLAMA_LAN to "Ollama LAN",
            AndroidLlmProvider.GROK to "Grok",
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            providers.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = provider == value,
                    onClick = {
                        if (value == AndroidLlmProvider.GROK && !cloudConsent) return@SegmentedButton
                        viewModel.setLlmProvider(context, value)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, providers.size),
                    enabled = value != AndroidLlmProvider.GROK || cloudConsent,
                    modifier = Modifier.testTag("settings_llm_${value.storageValue}"),
                ) {
                    Text(label)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsToggleRow(
            title = "Allow cloud AI (Grok)",
            subtitle = "Journal and knowledge text may leave this device when Grok is selected",
            checked = cloudConsent,
            testTag = "settings_cloud_consent",
            onCheckedChange = { viewModel.setCloudConsent(context, it) },
        )
        if (cloudConsent) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = grokDraft,
                onValueChange = { grokDraft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_grok_api_key"),
                label = { Text("Grok API key") },
                placeholder = { Text("xai-…") },
                singleLine = true,
                visualTransformation = if (showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                shape = RoundedCornerShape(12.dp),
            )
            Row {
                TextButton(
                    onClick = {
                        viewModel.setGrokApiKey(context, grokDraft)
                    },
                    modifier = Modifier.testTag("settings_grok_save"),
                ) { Text("Save key") }
                TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "Hide" else "Show")
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = ollamaDraft,
            onValueChange = { ollamaDraft = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_ollama_url"),
            label = { Text("Ollama LAN URL") },
            placeholder = { Text("http://192.168.1.10:11434") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
        TextButton(
            onClick = {
                if (viewModel.setOllamaLanUrl(context, ollamaDraft)) {
                    ollamaDraft = ServeClient.normalizeBaseUrl(ollamaDraft)
                }
            },
            modifier = Modifier.testTag("settings_ollama_save"),
        ) { Text("Save Ollama URL") }
        Text(
            "Ollama must be a private or loopback address (same LAN gate as Mac serve).",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OnDeviceAiSettingsSection(viewModel: MainViewModel) {
    val context = LocalContext.current
    val view = LocalView.current
    val enabled by viewModel.onDeviceAiEnabled.collectAsState()
    val anySupported by viewModel.anyAiSupported.collectAsState()
    val availability by viewModel.aiFeatureAvailability.collectAsState()
    val releaseStage by viewModel.nanoReleaseStage.collectAsState()
    val modelPreference by viewModel.nanoModelPreference.collectAsState()
    val baseModelName by viewModel.nanoBaseModelName.collectAsState()
    val usingFallback by viewModel.nanoUsingStableFullFallback.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshAiAvailability(context)
    }

    SettingsSection(title = "On-device AI") {
        Text(
            "Gemini Nano runs locally via AICore. Nothing is sent to the cloud. Suggestions are never applied until you accept them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (!anySupported && availability.isNotEmpty() &&
            availability.values.all { it is AiAvailability.Unavailable }
        ) {
            Text(
                "Unsupported on this device. On-device AI needs AICore (Pixel 9+ / select flagships).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings_ai_unsupported"),
            )
        } else {
            SettingsToggleRow(
                title = "Enable on-device AI",
                subtitle = if (anySupported) {
                    "Tag/mood suggestions, proofread, rewrite, summarize, digests, week/month rollups, image description"
                } else {
                    "Checking device support…"
                },
                checked = enabled,
                enabled = anySupported || availability.isEmpty(),
                testTag = "settings_ai_master_toggle",
                onCheckedChange = { viewModel.setOnDeviceAiEnabled(context, it) },
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Prompt model",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val stages = listOf(NanoReleaseStage.STABLE, NanoReleaseStage.PREVIEW)
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_ai_release_stage"),
            ) {
                stages.forEachIndexed { index, stage ->
                    SegmentedButton(
                        selected = releaseStage == stage,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            viewModel.setNanoReleaseStage(context, stage)
                        },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index, stages.size),
                        modifier = Modifier.testTag(
                            "settings_ai_stage_${stage.name.lowercase()}",
                        ),
                    ) {
                        Text(
                            when (stage) {
                                NanoReleaseStage.STABLE -> "Stable"
                                NanoReleaseStage.PREVIEW -> "Preview"
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            val prefs = listOf(NanoModelPreference.FULL, NanoModelPreference.FAST)
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_ai_model_preference"),
            ) {
                prefs.forEachIndexed { index, preference ->
                    SegmentedButton(
                        selected = modelPreference == preference,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            viewModel.setNanoModelPreference(context, preference)
                        },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index, prefs.size),
                        modifier = Modifier.testTag(
                            "settings_ai_pref_${preference.name.lowercase()}",
                        ),
                    ) {
                        Text(
                            when (preference) {
                                NanoModelPreference.FULL -> "Full"
                                NanoModelPreference.FAST -> "Fast"
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                buildString {
                    append("Base model: ")
                    append(baseModelName?.takeIf { it.isNotBlank() } ?: "—")
                    if (usingFallback) {
                        append(" (using Stable · Full; preview/fast unavailable)")
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings_ai_base_model"),
            )
            Text(
                "Preview models require AICore Developer Preview enrollment on the device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .testTag("settings_ai_preview_note"),
            )
            Spacer(modifier = Modifier.height(12.dp))
            AiFeature.entries.forEach { feature ->
                val status = availability[feature] ?: AiAvailability.Checking
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .testTag("settings_ai_feature_${feature.name.lowercase()}"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(aiFeatureLabel(feature), fontWeight = FontWeight.Medium)
                        Text(
                            aiAvailabilityLabel(status),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (status is AiAvailability.Downloadable && enabled) {
                        TextButton(
                            onClick = { viewModel.downloadAiFeature(context, feature) },
                            modifier = Modifier.testTag("settings_ai_download_${feature.name.lowercase()}"),
                        ) {
                            Text("Download")
                        }
                    }
                }
            }
        }
    }
}

private fun aiFeatureLabel(feature: AiFeature): String = when (feature) {
    AiFeature.PROMPT -> "Prompt / digests"
    AiFeature.PROOFREAD -> "Proofreading"
    AiFeature.REWRITE -> "Rewriting"
    AiFeature.SUMMARIZE -> "Summarization"
    AiFeature.IMAGE_DESCRIPTION -> "Image description"
}

private fun aiAvailabilityLabel(status: AiAvailability): String = when (status) {
    is AiAvailability.Checking -> "Checking…"
    is AiAvailability.Unavailable -> "Unavailable"
    is AiAvailability.Downloadable -> "Ready to download"
    is AiAvailability.Downloading -> {
        if (status.bytesDownloaded > 0L) {
            "Downloading… ${status.bytesDownloaded / 1024} KB"
        } else {
            "Downloading…"
        }
    }
    is AiAvailability.Available -> "Ready"
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(16.dp),
                )
                .padding(16.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(testTag),
        )
    }
}
