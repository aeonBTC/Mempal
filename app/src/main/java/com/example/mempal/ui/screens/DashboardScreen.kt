package com.example.mempal.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.example.mempal.api.DifficultyAdjustment
import com.example.mempal.api.FeeRates
import com.example.mempal.api.HashrateInfo
import com.example.mempal.api.MempoolInfo
import com.example.mempal.api.NetworkClient
import com.example.mempal.cache.DashboardCache
import com.example.mempal.repository.SettingsRepository
import com.example.mempal.tor.TorManager
import com.example.mempal.tor.TorStatus
import com.example.mempal.ui.theme.AppColors
import com.example.mempal.viewmodel.DashboardUiState
import com.example.mempal.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

@Composable
fun DashboardContent(
    viewModel: MainViewModel,
    uiState: DashboardUiState,
    modifier: Modifier = Modifier,
    pullToRefreshState: PullToRefreshState? = null
) {
    val blockHeight by viewModel.blockHeight.observeAsState()
    val blockTimestamp by viewModel.blockTimestamp.observeAsState()
    val feeRates by viewModel.feeRates.observeAsState()
    val mempoolInfo by viewModel.mempoolInfo.observeAsState()
    val hashrateInfo by viewModel.hashrateInfo.observeAsState()
    val difficultyAdjustment by viewModel.difficultyAdjustment.observeAsState()
    val isInitialized by NetworkClient.isInitialized.collectAsState()
    val torManager = TorManager.getInstance()
    val torStatus by torManager.torStatus.collectAsState()
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val isUsingOnion = settingsRepository.getApiUrl().contains(".onion")

    LaunchedEffect(torStatus, isUsingOnion) {
        if (isUsingOnion && torStatus == TorStatus.CONNECTED &&
            (blockHeight == null || feeRates == null || mempoolInfo == null)) {
            delay(100)
            val usePreciseFees = settingsRepository.settings.value.usePreciseFees
            viewModel.setUsePreciseFees(usePreciseFees)
            if (viewModel.shouldAutoRefresh() || !viewModel.hasInitialData) {
                viewModel.refreshData(usePreciseFees, isManualRefresh = false)
            }
        }
    }

    // Compute status message - using derivedStateOf without remember keys for proper reactive updates
    // The derivedStateOf will only recompute when any of the states it reads actually change
    val statusMessage by remember {
        derivedStateOf {
            val hasRecentData = blockHeight != null || feeRates != null
            val hasCachedData = DashboardCache.hasCachedData()
            val isTorEnabled = torManager.isTorEnabled()
            val currentTorStatus = torStatus
            val currentIsInitialized = isInitialized
            
            when {
                // If we have fresh data (not from cache), connection is working - no status message needed
                uiState is DashboardUiState.Success && !uiState.isCache -> null
                
                // If we have recent data, don't show reconnecting message - connection is clearly working
                hasRecentData -> null
                
                isUsingOnion && currentTorStatus != TorStatus.CONNECTED -> {
                    if (hasCachedData) "Waiting for Tor connection..."
                    else "Connecting to Tor network..."
                }
                isTorEnabled && (!currentIsInitialized || currentTorStatus == TorStatus.CONNECTING) -> {
                    if (hasCachedData) "Reconnecting to Tor network..."
                    else "Connecting to Tor network..."
                }
                !isTorEnabled && !currentIsInitialized -> {
                    if (hasCachedData) "Reconnecting to server..."
                    else "Connecting to server..."
                }
                uiState is DashboardUiState.Error -> {
                    if (isTorEnabled &&
                        (uiState.message.contains("Connecting to Tor") || uiState.message.contains("Reconnecting to Tor"))) {
                        if (hasCachedData) "Reconnecting to Tor network..."
                        else "Connecting to Tor network..."
                    } else {
                        uiState.message
                    }
                }
                uiState is DashboardUiState.Success && uiState.isCache -> {
                    if (isTorEnabled) {
                        if (hasCachedData) "Reconnecting to Tor network..."
                        else "Connecting to Tor network..."
                    } else {
                        if (hasCachedData) "Reconnecting to server..."
                        else "Connecting to server..."
                    }
                }
                else -> null
            }
        }
    }

    DashboardDisplay(
        blockHeight = blockHeight,
        blockTimestamp = blockTimestamp,
        feeRates = feeRates,
        mempoolInfo = mempoolInfo,
        hashrateInfo = hashrateInfo,
        difficultyAdjustment = difficultyAdjustment,
        modifier = modifier,
        viewModel = viewModel,
        statusMessage = statusMessage,
        pullToRefreshState = pullToRefreshState
    )
}

@Composable
private fun DashboardDisplay(
    blockHeight: Int?,
    blockTimestamp: Long?,
    feeRates: FeeRates?,
    mempoolInfo: MempoolInfo?,
    hashrateInfo: HashrateInfo?,
    difficultyAdjustment: DifficultyAdjustment?,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel? = null,
    statusMessage: String? = null,
    pullToRefreshState: PullToRefreshState? = null
) {
    val isInitialized = NetworkClient.isInitialized.collectAsState()
    val isMainRefreshing by viewModel?.isMainRefreshing?.collectAsState() ?: remember { mutableStateOf(false) }
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    var visibleCards by remember { mutableStateOf(settingsRepository.getVisibleCards()) }
    var isRefreshing by remember { mutableStateOf(false) }
    var lastRefreshTime by remember { mutableLongStateOf(0L) }
    var justFinishedRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            delay(10)
            isRefreshing = false
        }
    }

    // Track when refresh completes to prevent bounce
    LaunchedEffect(isMainRefreshing) {
        if (isMainRefreshing) {
            justFinishedRefreshing = false
        } else {
            // When refresh completes, set flag to prevent stretch for a brief moment
            justFinishedRefreshing = true
            delay(300) // Prevent stretch for 300ms after refresh completes
            justFinishedRefreshing = false
        }
    }

    val refreshAll = {
        if (!isMainRefreshing && isInitialized.value) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastRefreshTime >= 5000) {
                isRefreshing = true
                val usePreciseFees = settingsRepository.settings.value.usePreciseFees
                viewModel?.setUsePreciseFees(usePreciseFees)
                viewModel?.refreshData(usePreciseFees)
            }
        }
    }

    LaunchedEffect(Unit) {
        visibleCards = settingsRepository.getVisibleCards()
    }

    // Calculate target stretch scale based on pull-to-refresh distanceFraction
    // Only apply stretch during active pull gesture, not during refresh or after refresh completes
    // distanceFraction goes from 0 to 1 (and can exceed 1), we'll create a subtle stretch (1.0 to 1.04)
    val targetStretchScale = if (pullToRefreshState != null && 
                                 pullToRefreshState.distanceFraction > 0f && 
                                 !isMainRefreshing && 
                                 !isRefreshing &&
                                 !justFinishedRefreshing) {
        1f + (pullToRefreshState.distanceFraction.coerceIn(0f, 1f) * 0.04f) // Max 4% stretch
    } else {
        1f
    }
    
    // Animate the stretch scale smoothly to prevent pop/bounce when refresh completes
    val stretchScale by animateFloatAsState(
        targetValue = targetStretchScale,
        animationSpec = tween(durationMillis = 200),
        label = "stretch_scale"
    )

    // Get card order from settings
    val cardOrder = remember { settingsRepository.getCardOrder() }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .padding(bottom = 4.dp)
            .graphicsLayer(
                scaleY = stretchScale,
                transformOrigin = TransformOrigin(0.5f, 0f) // Pivot at top center so it stretches downward
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!statusMessage.isNullOrEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppColors.DarkerNavy)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp).padding(end = 8.dp),
                        color = AppColors.Orange,
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.DataGray
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Render cards in the saved order
        cardOrder.forEach { cardName ->
            when (cardName) {
                "Block Height" -> {
                    if (cardName in visibleCards) {
                        DataCard(
                            title = "Block Height",
                            icon = Icons.Default.Numbers,
                            content = blockHeight?.let { height ->
                                {
                                    val formattedHeight = remember(height) {
                                        String.format(Locale.US, "%,d", height) + if (height >= 1_000_000) " for" else ""
                                    }
                                    Column {
                                        Text(
                                            text = formattedHeight,
                                            style = MaterialTheme.typography.headlineLarge,
                                            color = AppColors.DataGray
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            blockTimestamp?.let { timestamp ->
                                                val elapsedMinutes = ((System.currentTimeMillis() / 1000 - timestamp) / 60).coerceAtLeast(0L)
                                                Text(
                                                    text = "$elapsedMinutes ${if (elapsedMinutes == 1L) "minute" else "minutes"} ago",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = AppColors.DataGray.copy(alpha = 0.7f)
                                                )
                                            }
                                            difficultyAdjustment?.timeAvg?.let { timeAvgMs ->
                                                val minutes = timeAvgMs / 1000.0 / 60.0
                                                val formattedMinutes = remember(minutes) {
                                                    String.format(Locale.US, "%.2f", minutes)
                                                }
                                                val valueColor = if (minutes <= 10.0) {
                                                    Color(0xFF4CAF50) // Green - 10.00 or below
                                                } else {
                                                    Color(0xFFE57373) // Red - above 10.00
                                                }
                                                Text(
                                                    text = buildAnnotatedString {
                                                        withStyle(style = SpanStyle(color = AppColors.DataGray.copy(alpha = 0.7f))) {
                                                            append("Avg: ")
                                                        }
                                                        withStyle(style = SpanStyle(color = valueColor)) {
                                                            append(formattedMinutes)
                                                        }
                                                        withStyle(style = SpanStyle(color = AppColors.DataGray.copy(alpha = 0.7f))) {
                                                            append(" min/block")
                                                        }
                                                    },
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            isLoading = blockHeight == null,
                            onRefresh = refreshAll,
                            isRefreshing = isRefreshing || isMainRefreshing
                        )
                    }
                }
                "Hashrate" -> {
                    if (cardName in visibleCards) {
                        DataCard(
                            title = "Hashrate",
                            icon = Icons.Default.Memory,
                            content = {
                                if (hashrateInfo != null) {
                                    // Memoize formatted strings to avoid recalculation on every recomposition
                                    val formattedHashrate = remember(hashrateInfo.currentHashrate) {
                                        formatHashrate(hashrateInfo.currentHashrate)
                                    }
                                    val formattedDifficulty = remember(hashrateInfo.currentDifficulty) {
                                        "Difficulty: ${formatDifficulty(hashrateInfo.currentDifficulty)}"
                                    }
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = formattedHashrate,
                                                style = MaterialTheme.typography.headlineLarge,
                                                color = AppColors.DataGray
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = formattedDifficulty,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = AppColors.DataGray.copy(alpha = 0.7f)
                                            )
                                            viewModel?.difficultyAdjustment?.value?.let { adjustment ->
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = if (adjustment.difficultyChange >= 0) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                                            contentDescription = if (adjustment.difficultyChange >= 0) "Difficulty increasing" else "Difficulty decreasing",
                                                            tint = if (adjustment.difficultyChange >= 0) Color(0xFF4CAF50) else Color(0xFFE57373),
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                        val formattedChange = remember(adjustment.difficultyChange) {
                                                            String.format(Locale.getDefault(), "%.2f%%", abs(adjustment.difficultyChange))
                                                        }
                                                        Text(
                                                            text = formattedChange,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = if (adjustment.difficultyChange >= 0) Color(0xFF4CAF50) else Color(0xFFE57373)
                                                        )
                                                    }
                                                    val formattedBlocks = remember(adjustment.remainingBlocks) {
                                                        "in " + String.format(Locale.US, "%,d", adjustment.remainingBlocks) + " blocks"
                                                    }
                                                    Text(
                                                        text = formattedBlocks,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = AppColors.DataGray.copy(alpha = 0.7f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "Loading...",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = AppColors.DataGray.copy(alpha = 0.6f)
                                    )
                                }
                            },
                            isLoading = hashrateInfo == null,
                            onRefresh = refreshAll,
                            isRefreshing = isRefreshing || isMainRefreshing
                        )
                    }
                }
                "Mempool Size" -> {
                    if (cardName in visibleCards) {
                        DataCard(
                            title = "Mempool Size",
                            icon = Icons.Default.Speed,
                            content = mempoolInfo?.let {
                                {
                                    val mempoolSizeMB = it.vsize / 1_000_000.0
                                    val formattedSize = remember(mempoolSizeMB) {
                                        String.format(Locale.US, "%.2f vMB", mempoolSizeMB)
                                    }
                                    Column {
                                        Text(
                                            text = formattedSize,
                                            style = MaterialTheme.typography.headlineLarge,
                                            color = AppColors.DataGray
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val blocksToClean = ceil(mempoolSizeMB / 1.5).toInt()
                                            Text(
                                                text = "$blocksToClean ${if (blocksToClean == 1) "block" else "blocks"} to clear",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = AppColors.DataGray.copy(alpha = 0.7f)
                                            )
                                            if (it.unconfirmedCount > 0) {
                                                val formattedTxCount = remember(it.unconfirmedCount) {
                                                    String.format(Locale.US, "%,d transactions", it.unconfirmedCount)
                                                }
                                                Text(
                                                    text = formattedTxCount,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = AppColors.DataGray.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            isLoading = mempoolInfo == null,
                            onRefresh = refreshAll,
                            isRefreshing = isRefreshing || isMainRefreshing
                        )
                    }
                }
                "Fee Rates" -> {
                    if (cardName in visibleCards) {
                        val currentSettings by settingsRepository.settings.collectAsState()
                        val usePreciseFees = currentSettings.usePreciseFees
                        DataCard(
                            title = "Fee Rates",
                            content = feeRates?.let { { FeeRatesContent(it, usePreciseFees) } },
                            icon = Icons.Default.Timeline,
                            tooltip = "This section shows the average recommended fee rate with estimated confirmation times." +
                                    "\n\nNOTE: The mempool can sometimes experience a flood of transactions, leading to drastically higher fee rates. " +
                                    "These floods are often only a few vMB and clear quickly. To avoid overpaying fees, use the " +
                                    "\"Fee Distribution\" table to gauge the size and clearing time of the flood.",
                            warningTooltip = if (feeRates?.isUsingFallbackPreciseFees == true) {
                                "This mempool server doesn't support precise feerates. " +
                                        "Mempool.space will be used as a fallback source for this information."
                            } else null,
                            isLoading = feeRates == null,
                            onRefresh = refreshAll,
                            isRefreshing = isRefreshing || isMainRefreshing
                        )
                    }
                }
                "Fee Distribution" -> {
                    if (cardName in visibleCards) {
                        val currentSettings by settingsRepository.settings.collectAsState()
                        val usePreciseFees = currentSettings.usePreciseFees
                        DataCard(
                            title = "Fee Distribution",
                            content = mempoolInfo?.let { { HistogramContent(it, usePreciseFees) } },
                            icon = Icons.Default.BarChart,
                            tooltipComposable = { FeeDistributionTooltip() },
                            warningTooltip = if (mempoolInfo?.isUsingFallbackHistogram == true) {
                                "This mempool server doesn't provide fee distribution data. " +
                                        "Mempool.space will be used as a fallback source for this information."
                            } else null,
                            isLoading = mempoolInfo == null,
                            onRefresh = refreshAll,
                            isRefreshing = isRefreshing || isMainRefreshing
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DataCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    value: String? = null,
    content: (@Composable () -> Unit)? = null,
    tooltip: String? = null,
    tooltipComposable: (@Composable () -> Unit)? = null,
    warningTooltip: String? = null,
    isLoading: Boolean = value == null && content == null,
    onRefresh: (() -> Unit)? = null,
    isRefreshing: Boolean = false
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = null,
                indication = null,
                enabled = !isLoading && !isRefreshing,
                onClick = { onRefresh?.invoke() }
            ),
        colors = CardDefaults.cardColors(containerColor = AppColors.DarkerNavy)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .graphicsLayer(alpha = if (isRefreshing) 0.5f else 1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AppColors.Orange,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppColors.Orange
                )
                if (tooltip != null || tooltipComposable != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    if (tooltipComposable != null) {
                        TooltipButton(tooltip = tooltipComposable)
                    } else if (tooltip != null) {
                        TooltipButton(tooltip = tooltip)
                    }
                }
                if (warningTooltip != null) {
                    Spacer(modifier = Modifier.weight(1f))
                    TooltipButton(
                        tooltip = warningTooltip,
                        icon = Icons.Default.Warning,
                        tint = AppColors.Orange
                    )
                }
            }

            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppColors.DataGray
                )
            } else if (content != null) {
                content()
            } else if (isLoading) {
                Text(
                    text = "Loading...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.DataGray.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun FeeDistributionTooltip() {
    val greenColor = Color(0xFF4CAF50)
    val yellowColor = Color(0xFFFFA500)
    val redColor = AppColors.WarningRed
    
    Text(
        text = buildAnnotatedString {
            append("This section shows a detailed breakdown of the current mempool. Fee ranges are shown on the left and ")
            append("the cumulative size of the transactions is on the right")
            append("\n\nRange Key:")
            append("\n- ")
            withStyle(style = SpanStyle(color = greenColor)) {
                append("Green")
            }
            append(" will confirm in the next block.")
            append("\n- ")
            withStyle(style = SpanStyle(color = yellowColor)) {
                append("Yellow")
            }
            append(" might confirm in the next block.")
            append("\n- ")
            withStyle(style = SpanStyle(color = redColor)) {
                append("Red")
            }
            append(" will not confirm in the next block.")
            append("\n\nEach Bitcoin block confirms ~1.5 vMB worth of transactions.")
        },
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White
    )
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
private fun TooltipButton(
    tooltip: String,
    icon: ImageVector = Icons.Default.Info,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    TooltipButton(
        tooltip = {
            Text(
                text = tooltip,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
        },
        icon = icon,
        tint = tint
    )
}

@Composable
private fun FeeRatesContent(feeRates: FeeRates, usePreciseFees: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FeeRateRow("Next Block", feeRates.fastestFee, usePreciseFees)
        FeeRateRow("In 3 Blocks", feeRates.halfHourFee, usePreciseFees)
        FeeRateRow("In 6 Blocks", feeRates.hourFee, usePreciseFees)
        FeeRateRow("In 1 Day", feeRates.economyFee, usePreciseFees)
    }
}

@Composable
private fun FeeRateRow(
    label: String,
    value: Double?,
    usePreciseFees: Boolean,
    modifier: Modifier = Modifier
) {
    // Memoize formatted fee value to avoid recalculation on every recomposition
    val formattedValue = remember(value, usePreciseFees) {
        if (value != null) {
            if (usePreciseFees) {
                if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.2f", value)
            } else {
                value.toInt().toString()
            }
        } else "..."
    }
    
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formattedValue,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (value != null) {
                Text(
                    text = "sat/vB",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun HistogramContent(mempoolInfo: MempoolInfo, usePreciseFees: Boolean) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    var lastValidHistogram by remember { mutableStateOf<List<List<Double>>>(emptyList()) }
    var isExpanded by remember { mutableStateOf(settingsRepository.isFeeDistributionExpanded()) }

    LaunchedEffect(mempoolInfo.feeHistogram) {
        if (mempoolInfo.feeHistogram.isNotEmpty()) {
            lastValidHistogram = mempoolInfo.feeHistogram
        }
    }

    LaunchedEffect(isExpanded) {
        settingsRepository.setFeeDistributionExpanded(isExpanded)
    }

    val histogramToDisplay = mempoolInfo.feeHistogram.ifEmpty { lastValidHistogram }

    // Memoize the expensive histogram processing - only recalculate when inputs change
    val processedEntries = remember(histogramToDisplay, usePreciseFees) {
        processHistogramEntries(histogramToDisplay, usePreciseFees)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (processedEntries.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Range (sat/vB)",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Default
                )
                Text(
                    text = "Sum",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Default
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )
            
            // Limit to top 30 entries when collapsed
            val displayedEntries = remember(processedEntries, isExpanded) {
                if (isExpanded || processedEntries.size <= 30) {
                    processedEntries
                } else {
                    processedEntries.take(30)
                }
            }
            
            displayedEntries.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = entry.rangeStr,
                        style = MaterialTheme.typography.titleLarge,
                        color = entry.color,
                        fontFamily = FontFamily.Default
                    )
                    Text(
                        text = entry.formattedSum,
                        style = MaterialTheme.typography.titleLarge,
                        color = entry.color,
                        fontFamily = FontFamily.Default
                    )
                }
            }

            // Show expand/collapse button if there are more than 30 entries
            if (processedEntries.size > 30) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isExpanded = !isExpanded },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = AppColors.Orange,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isExpanded) "Show Less" else "Show All (${processedEntries.size} entries)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.Orange
                    )
                }
            } else {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Loading...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.DataGray.copy(alpha = 0.6f)
                )
            }
        }
    }
}

// Histogram processing helper functions - moved outside composable to avoid recreation on each recomposition
private fun getHistogramRangeString(fee: Double): String {
    return when {
        fee >= 5000 -> "5000+"
        fee >= 2000 -> {
            val lower = (fee / 200).toInt() * 200
            val upper = lower + 200
            "$lower - $upper"
        }
        fee >= 1000 -> {
            val lower = (fee / 100).toInt() * 100
            val upper = lower + 100
            "$lower - $upper"
        }
        fee >= 100 -> {
            val lower = (fee / 10).toInt() * 10
            val upper = lower + 10
            "$lower - $upper"
        }
        else -> {
            val lower = fee.toInt()
            val upper = lower + 1
            "$lower - $upper"
        }
    }
}

private fun formatHistogramRangeString(lower: Double, upper: Double): String {
    return when {
        lower >= 5000 -> "5000+"
        else -> {
            // Always show 2 decimal places for consistency (e.g., 1.00 instead of 1)
            val lowerStr = String.format(Locale.US, "%.2f", lower)
            val upperStr = String.format(Locale.US, "%.2f", upper)
            "$lowerStr - $upperStr"
        }
    }
}

private fun createHistogramRangesFromFees(feeSizePairs: List<Pair<Double, Double>>): List<Pair<String, Double>> {
    if (feeSizePairs.isEmpty()) return emptyList()
    
    // Separate zero fees from non-zero fees
    val zeroFeeSize = feeSizePairs.filter { it.first == 0.0 }.sumOf { it.second }
    val nonZeroFees = feeSizePairs.filter { it.first > 0.0 }
    
    // Find the lowest non-zero fee
    val lowestNonZeroFee = nonZeroFees.minOfOrNull { it.first }
    
    // Sort non-zero fees by fee descending
    val sorted = nonZeroFees.sortedByDescending { it.first }
    
    if (sorted.isEmpty()) {
        // Only zero fees
        if (zeroFeeSize > 0.0) {
            return listOf(Pair("0", zeroFeeSize))
        }
        return emptyList()
    }
    
    // Data class to hold range info during processing
    data class RangeData(var start: Double, var end: Double, var size: Double)
    
    val ranges = mutableListOf<RangeData>()
    
    // First pass: create initial ranges with dynamic thresholds
    fun determineRangeThreshold(fee: Double): Double {
        return when {
            fee >= 1000 -> 50.0
            fee >= 100 -> 10.0
            fee >= 10 -> 2.0
            fee >= 1 -> 0.5    // More aggressive grouping for fees 1-10
            else -> 0.05       // For fees < 1
        }
    }
    
    sorted.forEach { (fee, size) ->
        if (ranges.isEmpty()) {
            ranges.add(RangeData(fee, fee, size))
        } else {
            val lastRange = ranges.last()
            val threshold = determineRangeThreshold(lastRange.end)
            val gap = lastRange.end - fee
            
            // Group if gap is within threshold
            if (gap <= threshold) {
                lastRange.start = minOf(lastRange.start, fee)
                lastRange.end = maxOf(lastRange.end, fee)
                lastRange.size += size
            } else {
                ranges.add(RangeData(fee, fee, size))
            }
        }
    }
    
    // Second pass: merge single-value ranges with nearest neighbor
    // A single-value range is one where start == end
    // Ranges are sorted DESCENDING (higher fees first), so:
    // - prevRange (i-1) has HIGHER fee values
    // - nextRange (i+1) has LOWER fee values
    fun mergeSingleRanges() {
        var i = 0
        while (i < ranges.size) {
            val range = ranges[i]
            if (range.start == range.end && ranges.size > 1) {
                // This is a single-value range, find closest neighbor to merge with
                val prevRange = if (i > 0) ranges[i - 1] else null  // Higher fees
                val nextRange = if (i < ranges.size - 1) ranges[i + 1] else null  // Lower fees
                
                // Gap to prev (higher): how far below prev's lowest point is our highest point
                val gapToPrev = prevRange?.let { it.start - range.end } ?: Double.MAX_VALUE
                // Gap to next (lower): how far above next's highest point is our lowest point  
                val gapToNext = nextRange?.let { range.start - it.end } ?: Double.MAX_VALUE
                
                // Merge with closest neighbor (prefer merging with next/lower to avoid upward creep)
                if (gapToNext <= gapToPrev && nextRange != null) {
                    // Merge with next range (lower fees)
                    nextRange.start = minOf(nextRange.start, range.start)
                    nextRange.end = maxOf(nextRange.end, range.end)
                    nextRange.size += range.size
                    ranges.removeAt(i)
                    // Don't increment i
                } else if (prevRange != null) {
                    // Merge with previous range (higher fees)
                    prevRange.start = minOf(prevRange.start, range.start)
                    prevRange.end = maxOf(prevRange.end, range.end)
                    prevRange.size += range.size
                    ranges.removeAt(i)
                    // Don't increment i
                } else {
                    // No neighbors to merge with, keep as is
                    i++
                }
            } else {
                i++
            }
        }
    }
    
    mergeSingleRanges()
    
    // Third pass: for any remaining single-value entries, expand to a small range
    fun expandSingleValues() {
        ranges.forEach { range ->
            if (range.start == range.end) {
                // Expand by a small amount based on fee magnitude
                val expansion = when {
                    range.start >= 100 -> 1.0
                    range.start >= 10 -> 0.5
                    range.start >= 1 -> 0.1
                    else -> 0.01
                }
                // Round to create cleaner range boundaries
                val roundedLower = floor(range.start / expansion) * expansion
                val roundedUpper = ceil(range.start / expansion) * expansion
                // Set range bounds, ensuring we have a range (not equal values)
                range.start = roundedLower
                range.end = if (roundedLower == roundedUpper) roundedUpper + expansion else roundedUpper
            }
        }
    }
    
    expandSingleValues()
    
    // Fourth pass: repeatedly merge overlapping/adjacent ranges until stable
    // Ranges are sorted DESCENDING, so prev (i-1) has higher fees, current (i) has lower fees
    // Overlap means: current's highest value >= prev's lowest value
    fun mergeOverlappingRanges() {
        var merged = true
        while (merged) {
            merged = false
            var i = ranges.size - 1
            while (i > 0) {
                val current = ranges[i]      // Lower fee range
                val prev = ranges[i - 1]     // Higher fee range
                
                // Check if ranges overlap or touch: current.end >= prev.start
                if (current.end >= prev.start - 0.005) {
                    // Merge current into prev
                    prev.start = minOf(prev.start, current.start)
                    prev.end = maxOf(prev.end, current.end)
                    prev.size += current.size
                    ranges.removeAt(i)
                    merged = true
                }
                i--
            }
        }
    }
    
    mergeOverlappingRanges()
    
    // Convert to final format - always show as range for precise fees
    val result = ranges.map { range ->
        Pair(formatHistogramRangeString(range.start, range.end), range.size)
    }.toMutableList()
    
    // Handle zero fee level - show as range from 0 to lowest range's start to avoid overlap
    if (zeroFeeSize > 0.0) {
        val lowestRangeStart = ranges.lastOrNull()?.start ?: lowestNonZeroFee ?: 0.0
        if (lowestRangeStart > 0.0) {
            result.add(Pair(formatHistogramRangeString(0.0, lowestRangeStart), zeroFeeSize))
        } else {
            result.add(Pair("0 - 0", zeroFeeSize))
        }
    }
    
    return result
}

// Data class for processed histogram entries with pre-computed display values
private data class ProcessedHistogramEntry(
    val rangeStr: String,
    val formattedSum: String,
    val color: Color
)

private fun processHistogramEntries(
    histogramData: List<List<Double>>,
    usePreciseFees: Boolean
): List<ProcessedHistogramEntry> {
    if (histogramData.isEmpty()) return emptyList()
    
    val entries = if (usePreciseFees) {
        // When precise fees are enabled, group by exact fee value first, then create ranges
        val groupedMap = mutableMapOf<Double, Double>()
        
        histogramData.forEach { feeRange ->
            if (feeRange.size >= 2) {
                val fee = feeRange[0]
                val size = feeRange[1]
                groupedMap[fee] = (groupedMap[fee] ?: 0.0) + size
            }
        }
        
        // Convert to list of (fee, size) pairs
        val feeSizePairs = groupedMap.entries
            .filter { it.value > 0 }
            .map { Pair(it.key, it.value) }
        
        // Create ranges from the fee values
        createHistogramRangesFromFees(feeSizePairs)
    } else {
        // When precise fees are disabled, use the original grouping logic
        val sizeMap = mutableMapOf<String, Double>()
        
        histogramData.forEach { feeRange ->
            if (feeRange.size >= 2) {
                val fee = feeRange[0]
                val size = feeRange[1]
                val rangeStr = getHistogramRangeString(fee)
                sizeMap[rangeStr] = (sizeMap[rangeStr] ?: 0.0) + size
            }
        }
        
        // Pre-compute numeric values for sorting to avoid parsing on every comparison
        sizeMap.entries
            .filter { it.value > 0 }
            .map { entry ->
                val sortValue = when {
                    entry.key.endsWith("+") -> entry.key.removeSuffix("+").toDouble()
                    else -> entry.key.split(" - ").first().toDouble()
                }
                Triple(entry.key, entry.value, sortValue)
            }
            .sortedByDescending { it.third }
            .map { Pair(it.first, it.second) }
    }
    
    // Calculate cumulative sum and format strings for all entries
    var totalRunningSum = 0.0
    return entries.map { (feeRangeStr, size) ->
        totalRunningSum += size
        val sumInMB = totalRunningSum / 1_000_000
        val formattedSum = String.format(Locale.US, "%.2f vMB", sumInMB)
        val color = when {
            sumInMB > 1.5 -> AppColors.WarningRed
            sumInMB > 1.2 -> Color(0xFFFFA500)
            else -> Color(0xFF4CAF50)
        }
        ProcessedHistogramEntry(feeRangeStr, formattedSum, color)
    }
}

private fun formatHashrate(hashrate: Double): String {
    val units = arrayOf("H/s", "KH/s", "MH/s", "GH/s", "TH/s", "PH/s", "EH/s", "ZH/s")
    var value = hashrate
    var unitIndex = 0

    while (value >= 1000 && unitIndex < units.size - 1) {
        value /= 1000
        unitIndex++
    }

    return String.format(Locale.US, "%.2f %s", value, units[unitIndex])
}

private fun formatDifficulty(difficulty: Double): String {
    val units = arrayOf("", "K", "M", "G", "T", "P", "E", "Z")
    var value = difficulty
    var unitIndex = 0

    while (value >= 1000 && unitIndex < units.size - 1) {
        value /= 1000
        unitIndex++
    }

    return String.format(Locale.US, "%.2f %s", value, units[unitIndex])
}
