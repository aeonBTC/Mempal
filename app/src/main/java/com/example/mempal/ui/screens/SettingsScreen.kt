package com.example.mempal.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.core.net.toUri
import com.example.mempal.BuildConfig
import com.example.mempal.R
import com.example.mempal.api.NetworkClient
import com.example.mempal.repository.SettingsRepository
import com.example.mempal.tor.TorManager
import com.example.mempal.tor.TorStatus
import com.example.mempal.ui.theme.AppColors
import com.example.mempal.viewmodel.MainViewModel
import com.example.mempal.widget.WidgetUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel? = null
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val scope = rememberCoroutineScope()
    val torManager = TorManager.getInstance()
    val torStatus by torManager.torStatus.collectAsState()
    var torEnabled by remember { mutableStateOf(torManager.isTorEnabled()) }
    var updateFrequency by remember { mutableLongStateOf(settingsRepository.getUpdateFrequency()) }

    val initialTorEnabled = rememberSaveable { torManager.isTorEnabled() }
    val initialApiUrl = rememberSaveable { settingsRepository.getApiUrl() }

    var selectedOption by remember {
        mutableIntStateOf(
            if (settingsRepository.getApiUrl() == "https://mempool.space") 0 else 1
        )
    }
    val initialSelectedOption = rememberSaveable {
        if (settingsRepository.getApiUrl() == "https://mempool.space") 0 else 1
    }

    var customUrl by remember {
        mutableStateOf(
            if (settingsRepository.getApiUrl() != "https://mempool.space")
                settingsRepository.getApiUrl() else ""
        )
    }
    var showRestartDialog by remember { mutableStateOf(false) }
    var showUrlError by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }
    val savedServers = remember { mutableStateOf(settingsRepository.getSavedServers().toList()) }

    var visibleCards by remember { mutableStateOf(settingsRepository.getVisibleCards()) }

    val hasServerSettingsChanged = selectedOption != initialSelectedOption ||
            (selectedOption == 1 && customUrl != initialApiUrl) ||
            torEnabled != initialTorEnabled ||
            visibleCards != settingsRepository.getVisibleCards()

    fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }

    fun handleSave() {
        val newUrl = if (selectedOption == 0) {
            if (torManager.isTorEnabled()) {
                torManager.stopTor(context)
                torEnabled = false
            }
            "https://mempool.space"
        } else {
            customUrl.trim().trimEnd('/')
        }

        if (selectedOption == 1) {
            if (!isValidUrl(newUrl)) {
                showUrlError = true
                return
            }
            if (!newUrl.contains(".onion") && torManager.isTorEnabled()) {
                torManager.stopTor(context)
                torEnabled = false
            }
        }

        showUrlError = false
        settingsRepository.saveApiUrl(newUrl)
        torManager.saveTorState(torEnabled)
        settingsRepository.saveVisibleCards(visibleCards)
        showRestartDialog = true
    }

    suspend fun testServerConnection(url: String): Boolean {
        return try {
            val isOnion = url.contains(".onion")

            if (isOnion) {
                if (torManager.torStatus.value != TorStatus.CONNECTED) {
                    return false
                }
            }

            // Small delay to ensure network is ready
            delay(100L)

            val timeout = if (isOnion) 15000L else 5000L
            withTimeout(timeout) {
                val client = NetworkClient.createTestClient(url, useTor = isOnion)
                val response = client.getBlockHeight()
                response.isSuccessful && response.body() != null
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            false
        } catch (e: Exception) {
            android.util.Log.e("SettingsScreen", "Connection test failed: ${e.message}")
            false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Mempool Server Card
        Card(
            colors = CardDefaults.cardColors(containerColor = AppColors.DarkerNavy)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Mempool Server",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppColors.Orange
                )

                Column {
                    RadioOption(
                        text = "Default (mempool.space)",
                        selected = selectedOption == 0,
                        onClick = {
                            selectedOption = 0
                            if (torManager.isTorEnabled()) {
                                torManager.stopTor(context)
                                torEnabled = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    RadioOption(
                        text = "Custom Server",
                        selected = selectedOption == 1,
                        onClick = { selectedOption = 1 }
                    )

                    if (selectedOption == 1) {
                        var isDropdownExpanded by remember { mutableStateOf(false) }

                        fun isDefaultServer(url: String): Boolean {
                            val trimmed = url.trim()
                            return trimmed == "mempool.space" ||
                                    trimmed == "mempool.space/" ||
                                    trimmed == "https://mempool.space" ||
                                    trimmed == "https://mempool.space/"
                        }

                        ExposedDropdownMenuBox(
                            expanded = isDropdownExpanded,
                            onExpandedChange = { newValue ->
                                if (!newValue) isDropdownExpanded = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                        ) {
                            OutlinedTextField(
                                value = customUrl,
                                onValueChange = { url ->
                                    customUrl = url
                                    showUrlError = isDefaultServer(url)
                                    testResult = null
                                    if (url.contains(".onion")) {
                                        if (!torEnabled) {
                                            torEnabled = true
                                            torManager.startTor(context)
                                        }
                                    } else {
                                        if (torEnabled && url.isNotEmpty()) {
                                            torManager.stopTor(context)
                                            torEnabled = false
                                        }
                                    }
                                },
                                label = { Text(if (torEnabled) "Onion Address" else "Server Address", color = AppColors.DataGray) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AppColors.Orange,
                                    unfocusedBorderColor = AppColors.DataGray,
                                    unfocusedTextColor = AppColors.DataGray,
                                    focusedTextColor = AppColors.Orange
                                ),
                                isError = showUrlError,
                                supportingText = if (showUrlError) {
                                    {
                                        Text(
                                            text = if (isDefaultServer(customUrl))
                                                "Use default server option instead"
                                            else "URL must start with http:// or https://",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                } else null,
                                trailingIcon = {
                                    IconButton(onClick = { isDropdownExpanded = !isDropdownExpanded }) {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDropdownExpanded)
                                    }
                                }
                            )

                            ExposedDropdownMenu(
                                expanded = isDropdownExpanded,
                                onDismissRequest = { isDropdownExpanded = false },
                                modifier = Modifier
                                    .exposedDropdownSize()
                                    .heightIn(max = 250.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                savedServers.value.forEach { serverUrl ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = if (serverUrl.length > 26)
                                                        serverUrl.take(26) + "..."
                                                    else serverUrl,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = AppColors.DataGray,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                IconButton(
                                                    onClick = {
                                                        settingsRepository.removeSavedServer(serverUrl)
                                                        savedServers.value = settingsRepository.getSavedServers().toList()
                                                    },
                                                    modifier = Modifier.padding(start = 8.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = "Delete server",
                                                        tint = Color(0xFFEC910C)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            customUrl = serverUrl.trimEnd('/')
                                            if (serverUrl.contains(".onion")) {
                                                if (!torEnabled) {
                                                    torEnabled = true
                                                    torManager.startTor(context)
                                                }
                                            } else {
                                                if (torEnabled) {
                                                    torManager.stopTor(context)
                                                    torEnabled = false
                                                }
                                            }
                                            isDropdownExpanded = false
                                        },
                                        contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                        colors = MenuDefaults.itemColors(textColor = AppColors.DataGray)
                                    )
                                }

                                if (savedServers.value.isEmpty()) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "No saved servers",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = AppColors.DataGray.copy(alpha = 0.7f)
                                            )
                                        },
                                        onClick = { isDropdownExpanded = false },
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                        colors = MenuDefaults.itemColors(textColor = AppColors.DataGray)
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        color = when {
                                            selectedOption == 0 -> Color.Green
                                            isTestingConnection -> Color.Yellow
                                            testResult == true -> Color.Green
                                            testResult == false -> Color.Red
                                            else -> Color.Gray
                                        },
                                        shape = CircleShape
                                    )
                            )
                            Text(
                                text = when {
                                    selectedOption == 0 -> "Connected to mempool.space"
                                    isTestingConnection -> "Testing connection..."
                                    testResult == true -> "Connected successfully"
                                    testResult == false -> "Connection failed"
                                    else -> "Connection status"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.DataGray
                            )
                        }

                        LaunchedEffect(selectedOption, customUrl) {
                            if (selectedOption == 0) {
                                if (torEnabled) {
                                    torManager.stopTor(context)
                                    torEnabled = false
                                }
                            } else if (selectedOption == 1 && customUrl.isNotEmpty() && !customUrl.contains(".onion")) {
                                if (torEnabled) {
                                    torManager.stopTor(context)
                                    torEnabled = false
                                }
                            }
                        }

                        LaunchedEffect(customUrl, selectedOption, torEnabled, torStatus) {
                            if (selectedOption == 1 && customUrl.isNotEmpty() &&
                                (customUrl.startsWith("http://") || customUrl.startsWith("https://"))) {
                                isTestingConnection = true
                                testResult = null

                                // Always perform actual connection test - don't rely on isInitialized
                                // as it doesn't guarantee the server is actually reachable
                                delay(if (customUrl.contains(".onion")) 1000L else 500L)

                                if (torEnabled) {
                                    var currentTorStatus = torStatus
                                    var waited = 0L
                                    val maxWait = 10000L
                                    while (currentTorStatus != TorStatus.CONNECTED && waited < maxWait) {
                                        delay(500L)
                                        waited += 500L
                                        currentTorStatus = torManager.torStatus.value
                                    }
                                    if (currentTorStatus != TorStatus.CONNECTED) {
                                        testResult = false
                                        isTestingConnection = false
                                        return@LaunchedEffect
                                    }
                                }

                                // Retry logic for connection test
                                // Always perform actual connection test - createTestClient is independent
                                var retries = 0
                                val maxRetries = 2
                                var success = false
                                
                                while (retries <= maxRetries && !success) {
                                    success = testServerConnection(customUrl)
                                    if (!success && retries < maxRetries) {
                                        delay(500L)
                                    }
                                    retries++
                                }
                                
                                testResult = success
                                isTestingConnection = false
                            } else {
                                testResult = null
                                isTestingConnection = false
                            }
                        }
                    }
                }

                // Tor settings section
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Enable Tor",
                            style = MaterialTheme.typography.titleMedium,
                            color = AppColors.DataGray
                        )
                        if (torEnabled) {
                            Text(
                                text = "Status: ${torStatus.name}",
                                color = when (torStatus) {
                                    TorStatus.CONNECTED -> Color.Green
                                    TorStatus.CONNECTING -> Color.Yellow
                                    TorStatus.DISCONNECTED -> Color.Red
                                    TorStatus.ERROR -> Color.Red
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Text(
                                text = "Defaults to mempool.space's onion address.",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.DataGray.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    Switch(
                        checked = torEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && customUrl.isNotEmpty() && (!customUrl.startsWith("http://") && !customUrl.startsWith("https://"))) {
                                showUrlError = true
                                return@Switch
                            }
                            torEnabled = enabled
                            if (enabled) {
                                selectedOption = 1
                                torManager.startTor(context)
                                if (customUrl.isEmpty() || !customUrl.contains(".onion")) {
                                    customUrl = "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion"
                                }
                            } else {
                                torManager.stopTor(context)
                                if (customUrl.contains(".onion")) {
                                    customUrl = ""
                                }
                            }
                        },
                        enabled = torStatus != TorStatus.CONNECTING,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AppColors.Orange,
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = AppColors.DarkerNavy,
                            checkedBorderColor = AppColors.Orange,
                            uncheckedBorderColor = Color.Gray
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (selectedOption == 1 && (customUrl.isEmpty() || (!customUrl.startsWith("http://") && !customUrl.startsWith("https://")))) {
                                showUrlError = true
                                return@Button
                            }
                            if (selectedOption == 1) {
                                settingsRepository.saveApiUrl(customUrl)
                                settingsRepository.addSavedServer(customUrl)
                                savedServers.value = settingsRepository.getSavedServers().toList()
                            }
                            handleSave()
                        },
                        enabled = hasServerSettingsChanged &&
                                !(torEnabled && torStatus != TorStatus.CONNECTED),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.Orange,
                            disabledContainerColor = AppColors.Orange.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (torEnabled && torStatus != TorStatus.CONNECTED)
                                "Waiting for Tor..."
                            else
                                "Save"
                        )
                    }
                }
            }
        }

        // Fee Rates Settings Card
        Card(
            colors = CardDefaults.cardColors(containerColor = AppColors.DarkerNavy)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Fee Rates",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppColors.Orange
                )

                val currentSettings by settingsRepository.settings.collectAsState()
                var usePreciseFees by remember { mutableStateOf(currentSettings.usePreciseFees) }

                LaunchedEffect(currentSettings.usePreciseFees) {
                    usePreciseFees = currentSettings.usePreciseFees
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Precise Fees",
                            style = MaterialTheme.typography.titleMedium,
                            color = AppColors.DataGray
                        )
                        Text(
                            text = "Display fees with decimal accuracy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.DataGray.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Switch(
                        checked = usePreciseFees,
                        onCheckedChange = { enabled ->
                            usePreciseFees = enabled
                            val updatedSettings = currentSettings.copy(usePreciseFees = enabled)
                            settingsRepository.updateSettings(updatedSettings)

                            viewModel?.let { vm ->
                                scope.launch {
                                    vm.setUsePreciseFees(enabled)
                                    if (NetworkClient.isInitialized.value) {
                                        vm.refreshData(enabled)
                                    }
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AppColors.Orange,
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = AppColors.DarkerNavy,
                            checkedBorderColor = AppColors.Orange,
                            uncheckedBorderColor = Color.Gray
                        )
                    )
                }
            }
        }

        // Widget Settings Card
        Card(
            colors = CardDefaults.cardColors(containerColor = AppColors.DarkerNavy)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Widgets",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AppColors.Orange
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    TooltipButton(
                        tooltip = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = """If widgets don't auto-update, enable "unrestricted" battery usage for this app.""",
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Open battery settings",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickable {
                                            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.fromParts("package", context.packageName, null)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        }
                                )
                            }
                        }
                    )
                }

                var expanded by remember { mutableStateOf(false) }
                var showCustomHoursDialog by remember { mutableStateOf(false) }
                var customHoursText by remember { mutableStateOf("") }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = when (updateFrequency) {
                            15L -> "15 minutes"
                            30L -> "30 minutes"
                            60L -> "1 hour"
                            else -> if (updateFrequency > 60L) "${updateFrequency / 60L} hours" else "$updateFrequency minutes"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Update frequency") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.Orange,
                            focusedLabelColor = AppColors.Orange
                        )
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        listOf(15L, 30L, 60L, "custom").forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (option) {
                                            15L -> "15 minutes"
                                            30L -> "30 minutes"
                                            60L -> "1 hour"
                                            else -> "Custom..."
                                        }
                                    )
                                },
                                onClick = {
                                    if (option == "custom") {
                                        expanded = false
                                        showCustomHoursDialog = true
                                    } else {
                                        updateFrequency = option as Long
                                        settingsRepository.saveUpdateFrequency(option)
                                        WidgetUpdater.scheduleUpdates(context)
                                        expanded = false
                                    }
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }

                if (showCustomHoursDialog) {
                    AlertDialog(
                        onDismissRequest = { showCustomHoursDialog = false },
                        title = { Text("Set Custom Update Frequency") },
                        text = {
                            Column {
                                Text(
                                    "Enter update frequency in hours (1-24)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                OutlinedTextField(
                                    value = customHoursText,
                                    onValueChange = { value ->
                                        if (value.all { it.isDigit() } && value.length <= 2) {
                                            customHoursText = value
                                        }
                                    },
                                    label = { Text("Hours") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val hours = customHoursText.toIntOrNull()
                                    if (hours != null && hours in 1..24) {
                                        val minutes = hours * 60L
                                        updateFrequency = minutes
                                        settingsRepository.saveUpdateFrequency(minutes)
                                        WidgetUpdater.scheduleUpdates(context)
                                        showCustomHoursDialog = false
                                        customHoursText = ""
                                    }
                                }
                            ) {
                                Text("Save")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    showCustomHoursDialog = false
                                    customHoursText = ""
                                }
                            ) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                Text(
                    text = when {
                        updateFrequency >= 60L && updateFrequency % 60L == 0L -> {
                            val hours = updateFrequency / 60L
                            "Widgets will auto-update every ${if (hours == 1L) "1 hour" else "$hours hours"}."
                        }
                        else -> "Widgets will auto-update every $updateFrequency minutes."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.DataGray,
                    modifier = Modifier.padding(top = 4.dp, start = 12.dp)
                )
            }
        }

        // Notifications Settings Card
        Card(
            colors = CardDefaults.cardColors(containerColor = AppColors.DarkerNavy)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Notifications",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AppColors.Orange
                    )
                }

                var expanded by remember { mutableStateOf(false) }
                val isUsingCustomServer = settingsRepository.getApiUrl() != "https://mempool.space" &&
                        settingsRepository.getApiUrl() != "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion"
                val serverRestartComplete = !settingsRepository.needsRestartForServer()
                val timeUnitEnabled = isUsingCustomServer && serverRestartComplete

                val selectedTimeUnit = remember {
                    if (!timeUnitEnabled) {
                        settingsRepository.saveNotificationTimeUnit("minutes")
                        mutableStateOf("minutes")
                    } else {
                        mutableStateOf(settingsRepository.getNotificationTimeUnit())
                    }
                }

                LaunchedEffect(timeUnitEnabled) {
                    if (!timeUnitEnabled) {
                        selectedTimeUnit.value = "minutes"
                        settingsRepository.saveNotificationTimeUnit("minutes")
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { if (timeUnitEnabled) expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = when (selectedTimeUnit.value) {
                            "seconds" -> "Seconds"
                            else -> "Minutes"
                        },
                        onValueChange = {},
                        readOnly = true,
                        enabled = timeUnitEnabled,
                        label = { Text("Notification Interval") },
                        trailingIcon = {
                            if (timeUnitEnabled) {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .padding(top = 16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.Orange,
                            unfocusedBorderColor = AppColors.DataGray,
                            focusedLabelColor = AppColors.Orange,
                            disabledTextColor = AppColors.DataGray.copy(alpha = 0.7f),
                            disabledBorderColor = AppColors.DataGray.copy(alpha = 0.5f),
                            disabledLabelColor = AppColors.DataGray.copy(alpha = 0.7f)
                        )
                    )

                    if (timeUnitEnabled) {
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            listOf("minutes", "seconds").forEach { unit ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when (unit) {
                                                "minutes" -> "Minutes"
                                                else -> "Seconds"
                                            }
                                        )
                                    },
                                    onClick = {
                                        selectedTimeUnit.value = unit
                                        settingsRepository.saveNotificationTimeUnit(unit)
                                        expanded = false
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                )
                            }
                        }
                    }
                }

                Text(
                    text = if (!isUsingCustomServer) {
                        "To prevent excessive data requests to mempool.space, this option is only available when using a custom server."
                    } else if (!serverRestartComplete) {
                        "Save a custom server to enable this option."
                    } else {
                        when (selectedTimeUnit.value) {
                            "seconds" -> "Notification interval will be set to seconds."
                            else -> "Notification interval will be set to minutes."
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.DataGray,
                    modifier = Modifier.padding(top = 4.dp, start = 12.dp)
                )
            }
        }

        // Dashboard Cards section with drag-and-drop reordering
        Card(
            colors = CardDefaults.cardColors(containerColor = AppColors.DarkerNavy)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Dashboard",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AppColors.Orange
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Get card order from settings, ensuring all cards are included
                val allCards = listOf("Block Height", "Hashrate", "Mempool Size", "Fee Rates", "Fee Distribution")
                val savedOrder = settingsRepository.getCardOrder()
                // Merge saved order with any new cards that might have been added
                val initialOrder = (savedOrder.filter { it in allCards } + allCards.filter { it !in savedOrder }).distinct()
                
                val cardOrder = remember { initialOrder.toMutableStateList() }
                
                ReorderableCardList(
                    cardOrder = cardOrder,
                    visibleCards = visibleCards,
                    onVisibilityChange = { cardName, checked ->
                        visibleCards = if (checked) {
                            visibleCards + cardName
                        } else {
                            visibleCards - cardName
                        }
                        settingsRepository.saveVisibleCards(visibleCards)
                    },
                    onOrderChanged = {
                        settingsRepository.saveCardOrder(cardOrder.toList())
                    }
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Footer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
                .padding(bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = "https://github.com/aeonBTC/Mempal/".toUri()
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_github),
                    contentDescription = "GitHub Repository",
                    tint = AppColors.Orange
                )
            }

            Text(
                text = "Mempal v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.DataGray
            )
        }
    }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Restart Required") },
            text = { Text("Please restart the app to save your mempool server settings.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        (context as? Activity)?.finishAffinity()
                        Runtime.getRuntime().exit(0)
                    }
                ) {
                    Text("Restart Now")
                }
            }
        )
    }
}

@Composable
private fun RadioOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .selectable(selected = selected, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = AppColors.Orange),
            modifier = Modifier.padding(start = 16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.DataGray,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun TooltipButton(
    tooltip: @Composable () -> Unit,
    icon: ImageVector = Icons.Default.Info,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    var showTooltip by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { showTooltip = !showTooltip },
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "Info",
                modifier = Modifier.size(22.dp),
                tint = tint
            )
        }
        if (showTooltip) {
            Popup(
                onDismissRequest = { showTooltip = false },
                alignment = Alignment.BottomStart
            ) {
                Surface(
                    modifier = Modifier.padding(8.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = AppColors.DarkGray,
                    tonalElevation = 4.dp
                ) {
                    Box(modifier = Modifier.padding(12.dp)) {
                        tooltip()
                    }
                }
            }
        }
    }
}

@Composable
private fun ReorderableCardList(
    cardOrder: SnapshotStateList<String>,
    visibleCards: Set<String>,
    onVisibilityChange: (String, Boolean) -> Unit,
    onOrderChanged: () -> Unit
) {
    val density = LocalDensity.current
    val itemHeightDp = 56.dp
    val itemHeightPx = with(density) { itemHeightDp.toPx() }
    val itemSpacingPx = with(density) { 8.dp.toPx() } // vertical padding * 2
    val totalItemHeightPx = itemHeightPx + itemSpacingPx
    
    // Track drag state
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        cardOrder.forEachIndexed { index, cardName ->
            key(cardName) {
                val isDragging = draggedIndex == index
                
                // Calculate visual offset for non-dragged items when something is being dragged
                val displaceOffset = remember(draggedIndex, dragOffsetY, index) {
                    if (draggedIndex == null) return@remember 0f
                    val dragIdx = draggedIndex!!
                    val dragPosition = dragIdx * totalItemHeightPx + dragOffsetY
                    val myPosition = index * totalItemHeightPx
                    
                    when {
                        // Item being dragged - no displacement
                        index == dragIdx -> 0f
                        // Items below the dragged item that should move up
                        index > dragIdx && myPosition < dragPosition + totalItemHeightPx / 2 -> -totalItemHeightPx
                        // Items above the dragged item that should move down  
                        index < dragIdx && myPosition > dragPosition - totalItemHeightPx / 2 -> totalItemHeightPx
                        else -> 0f
                    }
                }
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .zIndex(if (isDragging) 1f else 0f)
                        .offset {
                            IntOffset(
                                x = 0,
                                y = if (isDragging) dragOffsetY.roundToInt() else displaceOffset.roundToInt()
                            )
                        }
                        .graphicsLayer {
                            shadowElevation = if (isDragging) 8f else 0f
                        }
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggedIndex = index
                                    dragOffsetY = 0f
                                },
                                onDragEnd = {
                                    // Calculate final position and reorder
                                    val dragIdx = draggedIndex
                                    if (dragIdx != null) {
                                        val targetIndex = (dragIdx + (dragOffsetY / totalItemHeightPx).roundToInt())
                                            .coerceIn(0, cardOrder.size - 1)
                                        
                                        if (targetIndex != dragIdx) {
                                            val item = cardOrder.removeAt(dragIdx)
                                            cardOrder.add(targetIndex, item)
                                            onOrderChanged()
                                        }
                                    }
                                    draggedIndex = null
                                    dragOffsetY = 0f
                                },
                                onDragCancel = {
                                    draggedIndex = null
                                    dragOffsetY = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (draggedIndex == index) {
                                        // Clamp the drag offset to list bounds
                                        val maxOffset = (cardOrder.size - 1 - index) * totalItemHeightPx
                                        val minOffset = -index * totalItemHeightPx
                                        dragOffsetY = (dragOffsetY + dragAmount.y).coerceIn(minOffset, maxOffset)
                                    }
                                }
                            )
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDragging) 
                            AppColors.Orange.copy(alpha = 0.2f) 
                        else 
                            AppColors.DataGray.copy(alpha = 0.05f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DragHandle,
                                contentDescription = "Drag to reorder",
                                tint = AppColors.DataGray.copy(alpha = 0.5f),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = cardName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (cardName in visibleCards)
                                    AppColors.DataGray
                                else
                                    AppColors.DataGray.copy(alpha = 0.5f)
                            )
                        }
                        Checkbox(
                            checked = cardName in visibleCards,
                            onCheckedChange = { checked ->
                                onVisibilityChange(cardName, checked)
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = AppColors.DataGray,
                                uncheckedColor = AppColors.DataGray.copy(alpha = 0.3f),
                                checkmarkColor = AppColors.DarkerNavy
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WelcomeDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Welcome to Mempal!",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Here are some quick tips to get you started.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Dashboard:",
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.Orange,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "•Tap or swipe down to refresh dashboard.\n•Tap Mempal logo to open block explorer.",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Widgets:",
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.Orange,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "•Tap any widget to refresh data.\n•Double tap widget to open Mempal app.",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Got it!")
            }
        }
    )
}
