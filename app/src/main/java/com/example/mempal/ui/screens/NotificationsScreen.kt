package com.example.mempal.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.core.content.ContextCompat
import com.example.mempal.model.FeeRateType
import com.example.mempal.model.NotificationSettings
import com.example.mempal.repository.SettingsRepository
import com.example.mempal.service.NotificationService
import com.example.mempal.ui.theme.AppColors
import android.widget.Toast
import kotlin.math.abs

// Data classes for notification configuration
data class NotificationSectionConfig(
    val title: String,
    val description: String,
    val enabled: Boolean,
    val frequency: Int,
    val onEnabledChange: (Boolean) -> Unit,
    val onFrequencyChange: (Int) -> Unit
)

data class SpecificBlockConfig(
    val enabled: Boolean,
    val frequency: Int,
    val targetHeight: Int?,
    val onEnabledChange: (Boolean) -> Unit,
    val onFrequencyChange: (Int) -> Unit,
    val onTargetHeightChange: (Int?) -> Unit
)

data class NewBlockConfig(
    val enabled: Boolean,
    val frequency: Int,
    val onEnabledChange: (Boolean) -> Unit,
    val onFrequencyChange: (Int) -> Unit
)

@Composable
fun NotificationsScreen(
    modifier: Modifier = Modifier,
    requestPermissionLauncher: ActivityResultLauncher<String>
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val settings by settingsRepository.settings.collectAsState(NotificationSettings())
    
    // Read time unit once here and pass to child composables to avoid redundant SharedPreferences reads
    val timeUnit = settingsRepository.getNotificationTimeUnit()
    val timeUnitLabel = if (timeUnit == "seconds") "seconds" else "minutes"

    val isAnyNotificationEnabled = settings.run {
        blockNotificationsEnabled && (newBlockNotificationEnabled || specificBlockNotificationEnabled) ||
                mempoolSizeNotificationsEnabled ||
                feeRatesNotificationsEnabled ||
                (txConfirmationEnabled && transactionId.isNotEmpty())
    }

    // Validation function to check if all required fields are filled for enabled notifications
    fun validateNotificationSettings(): String? {
        // Check Blocks notifications
        if (settings.blockNotificationsEnabled) {
            val hasNewBlock = settings.newBlockNotificationEnabled && settings.newBlockCheckFrequency > 0
            val hasSpecificBlock = settings.specificBlockNotificationEnabled && 
                    settings.specificBlockCheckFrequency > 0 && 
                    settings.targetBlockHeight != null
            
            if (!hasNewBlock && !hasSpecificBlock) {
                return "Please fill out required notification fields"
            }
        }
        
        // Check Fee Rates notifications
        if (settings.feeRatesNotificationsEnabled) {
            if (settings.feeRatesCheckFrequency <= 0 || settings.feeRateThreshold <= 0) {
                return "Please fill out required notification fields"
            }
        }
        
        // Check Transaction Confirmation notifications
        if (settings.txConfirmationEnabled) {
            if (settings.transactionId.isEmpty() || 
                settings.transactionId.length < 64 || 
                settings.txConfirmationFrequency <= 0) {
                return "Please fill out required notification fields"
            }
        }
        
        // Check Mempool Size notifications
        if (settings.mempoolSizeNotificationsEnabled) {
            if (settings.mempoolCheckFrequency <= 0 || settings.mempoolSizeThreshold <= 0) {
                return "Please fill out required notification fields"
            }
        }
        
        return null // All validations passed
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Button(
            onClick = {
                if (!settings.isServiceEnabled) {
                    // Validate that all required fields are filled for enabled notifications
                    val validationError = validateNotificationSettings()
                    if (validationError != null) {
                        Toast.makeText(context, validationError, Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    
                    // Enable the service first to capture user intent
                    settingsRepository.updateSettings(settings.copy(isServiceEnabled = true))
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            return@Button
                        }
                    }
                    val serviceIntent = Intent(context, NotificationService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } else {
                    val serviceIntent = Intent(context, NotificationService::class.java)
                    context.stopService(serviceIntent)
                    settingsRepository.updateSettings(settings.copy(isServiceEnabled = false))
                }
            },
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .align(Alignment.CenterHorizontally),
            enabled = settings.isServiceEnabled || isAnyNotificationEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (settings.isServiceEnabled) AppColors.WarningRed else AppColors.Orange,
                disabledContainerColor = AppColors.Orange.copy(alpha = 0.5f)
            )
        ) {
            Text(
                text = if (settings.isServiceEnabled) "Stop Notification Service" else "Start Notification Service",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = if (settings.isServiceEnabled || isAnyNotificationEnabled) 1f else 0.5f)
            )
        }

        LaunchedEffect(
            settings.blockNotificationsEnabled,
            settings.newBlockNotificationEnabled,
            settings.specificBlockNotificationEnabled,
            settings.mempoolSizeNotificationsEnabled,
            settings.feeRatesNotificationsEnabled,
            settings.txConfirmationEnabled,
            settings.transactionId
        ) {
            val isAnyEnabled = settings.run {
                blockNotificationsEnabled && (newBlockNotificationEnabled || specificBlockNotificationEnabled) ||
                        mempoolSizeNotificationsEnabled ||
                        feeRatesNotificationsEnabled ||
                        (txConfirmationEnabled && transactionId.isNotEmpty())
            }

            if (!isAnyEnabled && settings.isServiceEnabled) {
                val serviceIntent = Intent(context, NotificationService::class.java)
                context.stopService(serviceIntent)
                settingsRepository.updateSettings(settings.copy(isServiceEnabled = false))
            }
        }

        NotificationSection(
            config = NotificationSectionConfig(
                title = "Blocks",
                description = "Get notified when blocks are mined.",
                enabled = settings.blockNotificationsEnabled,
                frequency = settings.blockCheckFrequency,
                onEnabledChange = { newSettings ->
                    settingsRepository.updateSettings(settings.copy(blockNotificationsEnabled = newSettings))
                },
                onFrequencyChange = { newSettings ->
                    settingsRepository.updateSettings(settings.copy(blockCheckFrequency = newSettings))
                }
            ),
            timeUnitLabel = timeUnitLabel,
            newBlockConfig = NewBlockConfig(
                enabled = settings.newBlockNotificationEnabled,
                frequency = settings.newBlockCheckFrequency,
                onEnabledChange = { newSettings ->
                    settingsRepository.updateSettings(settings.copy(
                        newBlockNotificationEnabled = newSettings,
                        hasNotifiedForNewBlock = false
                    ))
                },
                onFrequencyChange = { newFrequency ->
                    settingsRepository.updateSettings(settings.copy(
                        newBlockCheckFrequency = newFrequency,
                        hasNotifiedForNewBlock = false
                    ))
                }
            ),
            specificBlockConfig = SpecificBlockConfig(
                enabled = settings.specificBlockNotificationEnabled,
                frequency = settings.specificBlockCheckFrequency,
                targetHeight = settings.targetBlockHeight,
                onEnabledChange = { newSettings ->
                    settingsRepository.updateSettings(settings.copy(
                        specificBlockNotificationEnabled = newSettings,
                        hasNotifiedForTargetBlock = false
                    ))
                },
                onFrequencyChange = { newFrequency ->
                    settingsRepository.updateSettings(settings.copy(
                        specificBlockCheckFrequency = newFrequency,
                        hasNotifiedForTargetBlock = false
                    ))
                },
                onTargetHeightChange = { newHeight ->
                    settingsRepository.updateSettings(settings.copy(
                        targetBlockHeight = newHeight,
                        hasNotifiedForTargetBlock = false
                    ))
                }
            )
        )

        FeeRatesNotificationSection(
            enabled = settings.feeRatesNotificationsEnabled,
            frequency = settings.feeRatesCheckFrequency,
            selectedFeeRateType = settings.selectedFeeRateType,
            threshold = settings.feeRateThreshold,
            isAboveThreshold = settings.feeRateAboveThreshold,
            timeUnitLabel = timeUnitLabel,
            onEnabledChange = { newSettings ->
                settingsRepository.updateSettings(settings.copy(feeRatesNotificationsEnabled = newSettings))
            },
            onFrequencyChange = { newSettings ->
                settingsRepository.updateSettings(settings.copy(feeRatesCheckFrequency = newSettings))
            },
            onFeeRateTypeChange = { newType ->
                settingsRepository.updateSettings(
                    settings.copy(
                        selectedFeeRateType = newType,
                        hasNotifiedForFeeRate = false
                    )
                )
            },
            onThresholdChange = { newThreshold ->
                settingsRepository.updateSettings(
                    settings.copy(
                        feeRateThreshold = newThreshold,
                        hasNotifiedForFeeRate = false
                    )
                )
            },
            onAboveThresholdChange = { isAbove ->
                settingsRepository.updateSettings(
                    settings.copy(
                        feeRateAboveThreshold = isAbove,
                        hasNotifiedForFeeRate = false
                    )
                )
            }
        )

        TransactionConfirmationSection(
            enabled = settings.txConfirmationEnabled,
            frequency = settings.txConfirmationFrequency,
            transactionId = settings.transactionId,
            timeUnitLabel = timeUnitLabel,
            onEnabledChange = { newSettings ->
                settingsRepository.updateSettings(settings.copy(txConfirmationEnabled = newSettings))
            },
            onFrequencyChange = { newSettings ->
                settingsRepository.updateSettings(settings.copy(txConfirmationFrequency = newSettings))
            },
            onTransactionIdChange = { newTxId ->
                settingsRepository.updateSettings(
                    settings.copy(
                        transactionId = newTxId,
                        hasNotifiedForCurrentTx = false
                    )
                )
            }
        )

        MempoolSizeNotificationSection(
            enabled = settings.mempoolSizeNotificationsEnabled,
            frequency = settings.mempoolCheckFrequency,
            threshold = settings.mempoolSizeThreshold,
            aboveThreshold = settings.mempoolSizeAboveThreshold,
            timeUnitLabel = timeUnitLabel,
            onEnabledChange = { newSettings ->
                settingsRepository.updateSettings(settings.copy(mempoolSizeNotificationsEnabled = newSettings))
            },
            onFrequencyChange = { newSettings ->
                settingsRepository.updateSettings(settings.copy(mempoolCheckFrequency = newSettings))
            },
            onThresholdChange = { newThreshold ->
                settingsRepository.updateSettings(
                    settings.copy(
                        mempoolSizeThreshold = newThreshold,
                        hasNotifiedForMempoolSize = false
                    )
                )
            },
            onAboveThresholdChange = { isAbove ->
                settingsRepository.updateSettings(
                    settings.copy(
                        mempoolSizeAboveThreshold = isAbove,
                        hasNotifiedForMempoolSize = false
                    )
                )
            }
        )
    }
}

@Composable
private fun NotificationSection(
    config: NotificationSectionConfig,
    timeUnitLabel: String,
    newBlockConfig: NewBlockConfig? = null,
    specificBlockConfig: SpecificBlockConfig? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.DarkerNavy)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = config.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppColors.Orange
                )
                Switch(
                    checked = config.enabled,
                    onCheckedChange = config.onEnabledChange,
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
            Text(
                text = config.description,
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.DataGray
            )

            if (config.enabled && newBlockConfig != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "New Block Alert",
                                style = MaterialTheme.typography.titleMedium,
                                color = AppColors.DataGray
                            )
                            Text(
                                text = "When a new block is mined.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.DataGray.copy(alpha = 0.7f)
                            )
                        }
                        Switch(
                            checked = newBlockConfig.enabled,
                            onCheckedChange = newBlockConfig.onEnabledChange,
                            modifier = Modifier.scale(0.8f),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = AppColors.Orange,
                                checkedBorderColor = AppColors.Orange,
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = AppColors.DarkerNavy,
                                uncheckedBorderColor = Color.Gray
                            )
                        )
                    }

                    if (newBlockConfig.enabled) {
                        NumericTextField(
                            value = if (newBlockConfig.frequency == 0) "" else newBlockConfig.frequency.toString(),
                            onValueChange = {
                                newBlockConfig.onFrequencyChange(if (it.isEmpty()) 0 else it.toIntOrNull() ?: 0)
                            },
                            label = "Check Interval ($timeUnitLabel)"
                        )
                    }
                }

                if (specificBlockConfig != null) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = AppColors.DataGray.copy(alpha = 0.5f)
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Block Height Alert",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AppColors.DataGray
                                )
                                Text(
                                    text = "When a specific block height is reached.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.DataGray.copy(alpha = 0.7f)
                                )
                            }
                            Switch(
                                checked = specificBlockConfig.enabled,
                                onCheckedChange = specificBlockConfig.onEnabledChange,
                                modifier = Modifier.scale(0.8f),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = AppColors.Orange,
                                    checkedBorderColor = AppColors.Orange,
                                    uncheckedThumbColor = Color.Gray,
                                    uncheckedTrackColor = AppColors.DarkerNavy,
                                    uncheckedBorderColor = Color.Gray
                                )
                            )
                        }

                        if (specificBlockConfig.enabled) {
                            var debouncedBlockHeight by remember(specificBlockConfig.targetHeight) {
                                mutableStateOf(specificBlockConfig.targetHeight?.toString() ?: "")
                            }

                            NumericTextField(
                                value = debouncedBlockHeight,
                                onValueChange = { newValue ->
                                    debouncedBlockHeight = newValue
                                    if (newValue.isEmpty()) {
                                        specificBlockConfig.onTargetHeightChange(null)
                                    } else {
                                        newValue.toIntOrNull()?.let { value ->
                                            if (value > 0) {
                                                specificBlockConfig.onTargetHeightChange(value)
                                            }
                                        }
                                    }
                                },
                                label = "Target Block Height"
                            )

                            NumericTextField(
                                value = if (specificBlockConfig.frequency == 0) "" else specificBlockConfig.frequency.toString(),
                                onValueChange = {
                                    specificBlockConfig.onFrequencyChange(if (it.isEmpty()) 0 else it.toIntOrNull() ?: 0)
                                },
                                label = "Check Interval ($timeUnitLabel)"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MempoolSizeNotificationSection(
    enabled: Boolean,
    frequency: Int,
    threshold: Float,
    aboveThreshold: Boolean,
    timeUnitLabel: String,
    onEnabledChange: (Boolean) -> Unit,
    onFrequencyChange: (Int) -> Unit,
    onThresholdChange: (Float) -> Unit,
    onAboveThresholdChange: (Boolean) -> Unit
) {
    var thresholdString by remember {
        mutableStateOf(
            if (threshold == 0f) "" else {
                threshold.toString().trimEnd('0').trimEnd('.')
            }
        )
    }

    LaunchedEffect(threshold) {
        val currentParsed = thresholdString.toFloatOrNull() ?: 0f
        if (abs(currentParsed - threshold) > 0.0001f) {
            thresholdString = if (threshold == 0f) "" else {
                threshold.toString().trimEnd('0').trimEnd('.')
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.DarkerNavy)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Mempool Size",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppColors.Orange
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AppColors.Orange,
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = AppColors.DarkerNavy
                    )
                )
            }
            Text(
                text = "Get notified when mempool size ${if (aboveThreshold) "rises above" else "falls below"} threshold.",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.DataGray
            )
            if (enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThresholdToggle(
                        isAboveThreshold = aboveThreshold,
                        onToggleChange = onAboveThresholdChange
                    )

                    DecimalTextField(
                        value = thresholdString,
                        onValueChange = { newValue ->
                            thresholdString = newValue
                            if (newValue.isEmpty()) {
                                onThresholdChange(0f)
                            } else {
                                newValue.toFloatOrNull()?.let { value ->
                                    if (value >= 0) {
                                        onThresholdChange(value)
                                    }
                                }
                            }
                        },
                        label = "Threshold (vMB)"
                    )

                    NumericTextField(
                        value = if (frequency == 0) "" else frequency.toString(),
                        onValueChange = {
                            onFrequencyChange(if (it.isEmpty()) 0 else it.toIntOrNull() ?: 0)
                        },
                        label = "Check Interval ($timeUnitLabel)"
                    )
                }
            }
        }
    }
}

@Composable
private fun FeeRatesNotificationSection(
    enabled: Boolean,
    frequency: Int,
    selectedFeeRateType: FeeRateType,
    threshold: Double,
    isAboveThreshold: Boolean,
    timeUnitLabel: String,
    onEnabledChange: (Boolean) -> Unit,
    onFrequencyChange: (Int) -> Unit,
    onFeeRateTypeChange: (FeeRateType) -> Unit,
    onThresholdChange: (Double) -> Unit,
    onAboveThresholdChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val currentSettings by settingsRepository.settings.collectAsState()
    val usePreciseFees = currentSettings.usePreciseFees

    var expanded by remember { mutableStateOf(false) }
    var debouncedThreshold by remember(threshold) { mutableDoubleStateOf(threshold) }
    var thresholdString by remember {
        mutableStateOf(
            if (threshold == 0.0) "" else {
                threshold.toString().trimEnd('0').trimEnd('.')
            }
        )
    }

    LaunchedEffect(threshold) {
        val currentParsed = thresholdString.toDoubleOrNull() ?: 0.0
        if (abs(currentParsed - threshold) > 0.0001) {
            thresholdString = if (threshold == 0.0) "" else {
                threshold.toString().trimEnd('0').trimEnd('.')
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.DarkerNavy)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Fee Rates",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppColors.Orange
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
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
            Text(
                text = "Get notified when fee rates ${if (isAboveThreshold) "rise above" else "fall below"} threshold.",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.DataGray
            )
            if (enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThresholdToggle(
                        isAboveThreshold = isAboveThreshold,
                        onToggleChange = onAboveThresholdChange
                    )

                    if (usePreciseFees) {
                        DecimalTextField(
                            value = thresholdString,
                            onValueChange = { newValue ->
                                thresholdString = newValue
                                if (newValue.isEmpty()) {
                                    debouncedThreshold = 0.0
                                    onThresholdChange(0.0)
                                } else {
                                    newValue.toDoubleOrNull()?.let { value ->
                                        if (value >= 0) {
                                            debouncedThreshold = value
                                            onThresholdChange(value)
                                        }
                                    }
                                }
                            },
                            label = "Threshold (sat/vB)"
                        )
                    } else {
                        NumericTextField(
                            value = if (debouncedThreshold == 0.0) "" else debouncedThreshold.toInt().toString(),
                            onValueChange = { newValue ->
                                if (newValue.isEmpty()) {
                                    debouncedThreshold = 0.0
                                    onThresholdChange(0.0)
                                } else {
                                    newValue.toIntOrNull()?.let { value ->
                                        if (value >= 0) {
                                            debouncedThreshold = value.toDouble()
                                            onThresholdChange(value.toDouble())
                                        }
                                    }
                                }
                            },
                            label = "Threshold (sat/vB)"
                        )
                    }

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = when (selectedFeeRateType) {
                                FeeRateType.NEXT_BLOCK -> "Next Block"
                                FeeRateType.THREE_BLOCKS -> "3 Blocks"
                                FeeRateType.SIX_BLOCKS -> "6 Blocks"
                                FeeRateType.DAY_BLOCKS -> "1 Day"
                            },
                            label = { Text("Fee Rate", color = AppColors.DataGray) },
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = AppColors.DataGray,
                                focusedBorderColor = AppColors.Orange,
                                unfocusedTextColor = AppColors.DataGray,
                                focusedTextColor = AppColors.Orange
                            )
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Next Block") },
                                onClick = {
                                    onFeeRateTypeChange(FeeRateType.NEXT_BLOCK)
                                    expanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("3 Blocks") },
                                onClick = {
                                    onFeeRateTypeChange(FeeRateType.THREE_BLOCKS)
                                    expanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("6 Blocks") },
                                onClick = {
                                    onFeeRateTypeChange(FeeRateType.SIX_BLOCKS)
                                    expanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("1 Day") },
                                onClick = {
                                    onFeeRateTypeChange(FeeRateType.DAY_BLOCKS)
                                    expanded = false
                                }
                            )
                        }
                    }

                    NumericTextField(
                        value = if (frequency == 0) "" else frequency.toString(),
                        onValueChange = {
                            onFrequencyChange(if (it.isEmpty()) 0 else it.toIntOrNull() ?: 0)
                        },
                        label = "Check Interval ($timeUnitLabel)"
                    )
                }
            }
        }
    }
}

@Composable
private fun TransactionConfirmationSection(
    enabled: Boolean,
    frequency: Int,
    transactionId: String,
    timeUnitLabel: String,
    onEnabledChange: (Boolean) -> Unit,
    onFrequencyChange: (Int) -> Unit,
    onTransactionIdChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.DarkerNavy)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Confirmation",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AppColors.Orange
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    TooltipButton(
                        tooltip = "CAUTION: This feature has privacy implications.\nIf you're concerned about privacy, be sure to use the " +
                                """"Enable Tor" option in settings or connect to your own custom mempool server.""",
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
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
            Text(
                text = "Get notified when a transaction is confirmed.",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.DataGray
            )
            if (enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = transactionId,
                        onValueChange = { newValue ->
                            if (newValue.isEmpty() || newValue.all { it.isLetterOrDigit() }) {
                                onTransactionIdChange(newValue)
                            }
                        },
                        label = { Text("Transaction ID", color = AppColors.DataGray) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = AppColors.DataGray,
                            focusedBorderColor = AppColors.Orange,
                            unfocusedTextColor = AppColors.DataGray,
                            focusedTextColor = AppColors.Orange
                        )
                    )
                    NumericTextField(
                        value = if (frequency == 0) "" else frequency.toString(),
                        onValueChange = {
                            onFrequencyChange(if (it.isEmpty()) 0 else it.toIntOrNull() ?: 0)
                        },
                        label = "Check Interval ($timeUnitLabel)"
                    )
                }
            }
        }
    }
}

@Composable
private fun TooltipButton(
    tooltip: String,
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
                        Text(
                            text = tooltip,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThresholdToggle(
    isAboveThreshold: Boolean,
    onToggleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Notify when:",
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.DataGray
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Below",
                color = if (!isAboveThreshold) AppColors.Orange else AppColors.DataGray
            )
            Switch(
                checked = isAboveThreshold,
                onCheckedChange = onToggleChange,
                modifier = Modifier.scale(0.8f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AppColors.DarkerNavy,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = AppColors.DarkerNavy,
                    checkedBorderColor = Color.Gray,
                    uncheckedBorderColor = Color.Gray
                )
            )
            Text(
                text = "Above",
                color = if (isAboveThreshold) AppColors.Orange else AppColors.DataGray
            )
        }
    }
}

@Composable
private fun DecimalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { newValue ->
            if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                onValueChange(newValue)
            }
        },
        label = { Text(label, color = AppColors.DataGray) },
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AppColors.Orange,
            unfocusedBorderColor = AppColors.DataGray,
            focusedTextColor = AppColors.Orange,
            unfocusedTextColor = AppColors.DataGray
        )
    )
}

@Composable
private fun NumericTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { newValue ->
            if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                onValueChange(newValue)
            }
        },
        label = { Text(label, color = AppColors.DataGray) },
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AppColors.Orange,
            unfocusedBorderColor = AppColors.DataGray,
            focusedTextColor = AppColors.Orange,
            unfocusedTextColor = AppColors.DataGray
        )
    )
}
