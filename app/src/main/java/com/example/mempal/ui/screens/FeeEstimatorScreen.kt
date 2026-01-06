package com.example.mempal.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.mempal.repository.SettingsRepository
import com.example.mempal.ui.theme.AppColors
import com.example.mempal.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.Locale

// Address type enum for fee estimation
enum class AddressType(val displayName: String, val inputVBytes: Double, val outputVBytes: Double) {
    LEGACY("Legacy (P2PKH)", 148.0, 34.0),
    SEGWIT("SegWit (P2WPKH)", 68.0, 31.0),
    TAPROOT("Taproot (P2TR)", 57.5, 43.0)
}

@Composable
fun FeeEstimatorScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel? = null,
    numInputs: String,
    onNumInputsChange: (String) -> Unit,
    numOutputs: String,
    onNumOutputsChange: (String) -> Unit,
    selectedAddressType: AddressType,
    onAddressTypeChange: (AddressType) -> Unit,
    selectedFeeRateOption: String,
    onFeeRateOptionChange: (String) -> Unit,
    customFeeRate: String,
    onCustomFeeRateChange: (String) -> Unit,
    isMultisig: Boolean,
    onMultisigChange: (Boolean) -> Unit,
    requiredSigs: String,
    onRequiredSigsChange: (String) -> Unit,
    totalKeys: String,
    onTotalKeysChange: (String) -> Unit
) {
    val feeRates = viewModel?.feeRates?.observeAsState()?.value
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val currentSettings by settingsRepository.settings.collectAsState()
    val usePreciseFees = currentSettings.usePreciseFees
    val scope = rememberCoroutineScope()

    fun formatFeeRate(value: Double): String {
        return if (usePreciseFees) {
            if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.2f", value)
        } else {
            value.toInt().toString()
        }
    }

    LaunchedEffect(feeRates) {
        if (feeRates != null && selectedFeeRateOption.isEmpty()) {
            onFeeRateOptionChange("3 Blocks")
        }
    }

    fun getAddressTypeDisplayName(addressType: AddressType, isMultisig: Boolean): String {
        return when {
            isMultisig && addressType == AddressType.LEGACY -> "Legacy (P2SH)"
            isMultisig && addressType == AddressType.SEGWIT -> "SegWit (P2WSH)"
            else -> addressType.displayName
        }
    }

    fun calculateMultisigInputSize(addressType: AddressType, m: Int, n: Int): Double {
        return when (addressType) {
            AddressType.LEGACY -> {
                val redeemScript = 3 + 34 * n
                val redeemScriptPush = if (redeemScript <= 75) 1 else if (redeemScript <= 255) 2 else 3
                val scriptSig = 1 + 73 * m + redeemScriptPush + redeemScript
                val scriptSigLengthVarint = if (scriptSig <= 252) 1 else 3
                36.0 + scriptSigLengthVarint + scriptSig + 4.0
            }
            AddressType.SEGWIT -> {
                val witnessScript = 3 + 34 * n
                val witnessScriptSizePrefix = if (witnessScript <= 252) 1 else 3
                val witnessBytes = 1 + 1 + 73 * m + witnessScriptSizePrefix + witnessScript
                41.0 + witnessBytes / 4.0
            }
            AddressType.TAPROOT -> 57.5
        }
    }

    fun calculateMultisigOutputSize(addressType: AddressType): Double {
        return when (addressType) {
            AddressType.LEGACY -> 32.0
            AddressType.SEGWIT -> 43.0
            AddressType.TAPROOT -> 43.0
        }
    }

    val inputCount = numInputs.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val outputCount = numOutputs.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val maxKeys = when (selectedAddressType) {
        AddressType.LEGACY -> 15
        AddressType.SEGWIT -> 20
        AddressType.TAPROOT -> 999
    }
    val m = requiredSigs.toIntOrNull()?.coerceIn(1, maxKeys) ?: 2
    val n = totalKeys.toIntOrNull()?.coerceIn(m, maxKeys) ?: 3

    val inputVBytes = if (isMultisig) {
        calculateMultisigInputSize(selectedAddressType, m, n)
    } else {
        selectedAddressType.inputVBytes
    }

    val txOverhead = if (selectedAddressType == AddressType.LEGACY) 10.0 else 10.5

    val outputVBytes = if (isMultisig) {
        calculateMultisigOutputSize(selectedAddressType)
    } else {
        selectedAddressType.outputVBytes
    }

    val totalVBytes = txOverhead + (inputVBytes * inputCount) + (outputVBytes * outputCount)

    val effectiveFeeRate = when {
        selectedFeeRateOption == "Custom" -> customFeeRate.toDoubleOrNull() ?: 1.0
        selectedFeeRateOption == "Next Block" && feeRates != null -> feeRates.fastestFee
        selectedFeeRateOption == "3 Blocks" && feeRates != null -> feeRates.halfHourFee
        selectedFeeRateOption == "6 Blocks" && feeRates != null -> feeRates.hourFee
        else -> 1.0
    }

    val estimatedFee = (totalVBytes * effectiveFeeRate).toLong()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Address Type Selection
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppColors.DarkerNavy)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Address Type",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppColors.Orange
                )
                Spacer(modifier = Modifier.height(12.dp))

                var addressTypeExpanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = addressTypeExpanded,
                    onExpandedChange = { addressTypeExpanded = !addressTypeExpanded }
                ) {
                    OutlinedTextField(
                        value = getAddressTypeDisplayName(selectedAddressType, isMultisig),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Address Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = addressTypeExpanded) },
                        supportingText = {
                            Text(
                                text = "Overhead: ${if (txOverhead % 1.0 == 0.0) txOverhead.toInt().toString() else txOverhead} vB | Input: ${if (inputVBytes % 1.0 == 0.0) inputVBytes.toInt().toString() else String.format(Locale.US, "%.2f", inputVBytes)} vB | Output: ${outputVBytes.toInt()} vB",
                                color = AppColors.DataGray.copy(alpha = 0.6f)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.Orange,
                            unfocusedBorderColor = AppColors.DataGray,
                            focusedLabelColor = AppColors.Orange,
                            unfocusedLabelColor = AppColors.DataGray,
                            focusedTextColor = AppColors.DataGray,
                            unfocusedTextColor = AppColors.DataGray
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = addressTypeExpanded,
                        onDismissRequest = { addressTypeExpanded = false },
                        containerColor = AppColors.DarkGray
                    ) {
                        AddressType.entries.forEach { addressType ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = getAddressTypeDisplayName(addressType, isMultisig),
                                        color = if (selectedAddressType == addressType) AppColors.Orange else AppColors.DataGray
                                    )
                                },
                                onClick = {
                                    onAddressTypeChange(addressType)
                                    addressTypeExpanded = false
                                },
                                colors = MenuDefaults.itemColors(textColor = AppColors.DataGray)
                            )
                        }
                    }
                }
            }
        }

        // Inputs and Outputs
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppColors.DarkerNavy)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Transaction Details",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppColors.Orange
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedTextField(
                        value = numInputs,
                        onValueChange = { if (it.all { c -> c.isDigit() }) onNumInputsChange(it) },
                        label = { Text("Inputs (UTXOs)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.Orange,
                            unfocusedBorderColor = AppColors.DataGray,
                            focusedLabelColor = AppColors.Orange,
                            unfocusedLabelColor = AppColors.DataGray,
                            focusedTextColor = AppColors.DataGray,
                            unfocusedTextColor = AppColors.DataGray
                        )
                    )
                    OutlinedTextField(
                        value = numOutputs,
                        onValueChange = { if (it.all { c -> c.isDigit() }) onNumOutputsChange(it) },
                        label = { Text("Outputs") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.Orange,
                            unfocusedBorderColor = AppColors.DataGray,
                            focusedLabelColor = AppColors.Orange,
                            unfocusedLabelColor = AppColors.DataGray,
                            focusedTextColor = AppColors.DataGray,
                            unfocusedTextColor = AppColors.DataGray
                        )
                    )
                }

                // Multisig toggle
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Multisig",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.DataGray
                    )
                    Switch(
                        checked = isMultisig,
                        onCheckedChange = { onMultisigChange(it) },
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

                // Multisig m-of-n configuration
                if (isMultisig) {
                    Spacer(modifier = Modifier.height(12.dp))
                    val maxMultisigKeys = when (selectedAddressType) {
                        AddressType.LEGACY -> 15
                        AddressType.SEGWIT -> 20
                        AddressType.TAPROOT -> 999
                    }
                    val maxDigits = if (selectedAddressType == AddressType.TAPROOT) 3 else 2

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = requiredSigs,
                            onValueChange = {
                                if (it.all { c -> c.isDigit() } && it.length <= maxDigits) {
                                    val numValue = it.toIntOrNull() ?: 0
                                    if (numValue <= maxMultisigKeys) {
                                        onRequiredSigsChange(it)
                                    }
                                }
                            },
                            label = { Text("Required (m)") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AppColors.Orange,
                                unfocusedBorderColor = AppColors.DataGray,
                                focusedLabelColor = AppColors.Orange,
                                unfocusedLabelColor = AppColors.DataGray,
                                focusedTextColor = AppColors.DataGray,
                                unfocusedTextColor = AppColors.DataGray
                            )
                        )
                        Text(
                            text = "of",
                            style = MaterialTheme.typography.bodyLarge,
                            color = AppColors.DataGray
                        )
                        OutlinedTextField(
                            value = totalKeys,
                            onValueChange = {
                                if (it.all { c -> c.isDigit() } && it.length <= maxDigits) {
                                    val numValue = it.toIntOrNull() ?: 0
                                    if (numValue <= maxMultisigKeys) {
                                        onTotalKeysChange(it)
                                    }
                                }
                            },
                            label = { Text("Total keys (n)") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AppColors.Orange,
                                unfocusedBorderColor = AppColors.DataGray,
                                focusedLabelColor = AppColors.Orange,
                                unfocusedLabelColor = AppColors.DataGray,
                                focusedTextColor = AppColors.DataGray,
                                unfocusedTextColor = AppColors.DataGray
                            )
                        )
                    }
                }

                Text(
                    text = "Transaction size: ${String.format(Locale.US, "%.2f", totalVBytes)} vBytes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.DataGray.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        // Fee Rate Input
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppColors.DarkerNavy)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                var rotationAngle by remember { mutableFloatStateOf(0f) }
                val animatedRotation by animateFloatAsState(
                    targetValue = rotationAngle,
                    animationSpec = tween(durationMillis = 500, easing = LinearEasing),
                    label = "refresh_rotation"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Fee Rate",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AppColors.Orange
                    )
                    IconButton(
                        onClick = {
                            rotationAngle += 360f
                            viewModel?.let { vm ->
                                scope.launch {
                                    val usePrecise = settingsRepository.settings.value.usePreciseFees
                                    vm.setUsePreciseFees(usePrecise)
                                    vm.refreshData(usePrecise, isManualRefresh = true)
                                }
                            }
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh fee rates",
                            tint = AppColors.Orange,
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer { rotationZ = animatedRotation }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                var feeRateExpanded by remember { mutableStateOf(false) }

                val feeRateDisplayValue = when {
                    selectedFeeRateOption == "Custom" -> customFeeRate
                    selectedFeeRateOption == "Next Block" && feeRates != null ->
                        "Next Block (${formatFeeRate(feeRates.fastestFee)} sat/vB)"
                    selectedFeeRateOption == "3 Blocks" && feeRates != null ->
                        "3 Blocks (${formatFeeRate(feeRates.halfHourFee)} sat/vB)"
                    selectedFeeRateOption == "6 Blocks" && feeRates != null ->
                        "6 Blocks (${formatFeeRate(feeRates.hourFee)} sat/vB)"
                    feeRates == null -> "Loading..."
                    else -> "Select fee rate"
                }

                ExposedDropdownMenuBox(
                    expanded = feeRateExpanded,
                    onExpandedChange = { feeRateExpanded = !feeRateExpanded }
                ) {
                    OutlinedTextField(
                        value = if (selectedFeeRateOption == "Custom") customFeeRate else feeRateDisplayValue,
                        onValueChange = {
                            if (selectedFeeRateOption == "Custom") {
                                val filtered = if (usePreciseFees) {
                                    it.filter { c -> c.isDigit() || c == '.' }
                                } else {
                                    it.filter { c -> c.isDigit() }
                                }
                                val numValue = filtered.toDoubleOrNull() ?: 0.0
                                if (numValue <= 1000000) {
                                    onCustomFeeRateChange(filtered)
                                }
                            }
                        },
                        readOnly = selectedFeeRateOption != "Custom",
                        label = { Text(if (selectedFeeRateOption == "Custom") "Custom fee rate (sat/vB)" else "Select Fee Rate") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = feeRateExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(if (selectedFeeRateOption == "Custom") MenuAnchorType.PrimaryEditable else MenuAnchorType.PrimaryNotEditable),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (usePreciseFees) KeyboardType.Decimal else KeyboardType.Number
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.Orange,
                            unfocusedBorderColor = AppColors.DataGray,
                            focusedLabelColor = AppColors.Orange,
                            unfocusedLabelColor = AppColors.DataGray,
                            focusedTextColor = AppColors.DataGray,
                            unfocusedTextColor = AppColors.DataGray
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = feeRateExpanded,
                        onDismissRequest = { feeRateExpanded = false },
                        containerColor = AppColors.DarkGray
                    ) {
                        if (feeRates != null) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Next Block (${formatFeeRate(feeRates.fastestFee)} sat/vB)",
                                        color = if (selectedFeeRateOption == "Next Block") AppColors.Orange else AppColors.DataGray
                                    )
                                },
                                onClick = {
                                    onFeeRateOptionChange("Next Block")
                                    feeRateExpanded = false
                                },
                                colors = MenuDefaults.itemColors(textColor = AppColors.DataGray)
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "3 Blocks (${formatFeeRate(feeRates.halfHourFee)} sat/vB)",
                                        color = if (selectedFeeRateOption == "3 Blocks") AppColors.Orange else AppColors.DataGray
                                    )
                                },
                                onClick = {
                                    onFeeRateOptionChange("3 Blocks")
                                    feeRateExpanded = false
                                },
                                colors = MenuDefaults.itemColors(textColor = AppColors.DataGray)
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "6 Blocks (${formatFeeRate(feeRates.hourFee)} sat/vB)",
                                        color = if (selectedFeeRateOption == "6 Blocks") AppColors.Orange else AppColors.DataGray
                                    )
                                },
                                onClick = {
                                    onFeeRateOptionChange("6 Blocks")
                                    feeRateExpanded = false
                                },
                                colors = MenuDefaults.itemColors(textColor = AppColors.DataGray)
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Custom",
                                    color = if (selectedFeeRateOption == "Custom") AppColors.Orange else AppColors.DataGray
                                )
                            },
                            onClick = {
                                onFeeRateOptionChange("Custom")
                                feeRateExpanded = false
                            },
                            colors = MenuDefaults.itemColors(textColor = AppColors.DataGray)
                        )
                    }
                }
            }
        }

        // Result Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppColors.Orange.copy(alpha = 0.15f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Estimated Fee",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.DataGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = String.format(Locale.US, "%,d", estimatedFee),
                    style = MaterialTheme.typography.headlineLarge,
                    color = AppColors.Orange
                )
                Text(
                    text = "sats",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.Orange.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${String.format(Locale.US, "%.2f", totalVBytes)} vB × ${formatFeeRate(effectiveFeeRate)} sat/vB",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.DataGray.copy(alpha = 0.7f)
                )
            }
        }

        // Info text
        Text(
            text = "Note: Actual transaction size may vary slightly depending on signatures and script complexity.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.DataGray.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}
