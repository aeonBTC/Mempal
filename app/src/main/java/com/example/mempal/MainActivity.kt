package com.example.mempal

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.example.mempal.api.FeeRates
import com.example.mempal.api.MempoolInfo
import com.example.mempal.api.NetworkClient
import com.example.mempal.api.Result
import com.example.mempal.model.FeeRateType
import com.example.mempal.model.NotificationSettings
import com.example.mempal.repository.SettingsRepository
import com.example.mempal.service.NotificationService
import com.example.mempal.tor.TorManager
import com.example.mempal.tor.TorStatus
import com.example.mempal.ui.theme.AppColors
import com.example.mempal.ui.theme.MempalTheme
import com.example.mempal.viewmodel.MainViewModel
import com.example.mempal.widget.WidgetUpdater
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ceil


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

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var settingsRepository: SettingsRepository

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted, proceed with notifications
            val settings = settingsRepository.settings.value
            if (settings.isServiceEnabled == true) {
                val serviceIntent = Intent(this, NotificationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
        } else {
            // Permission denied
            Toast.makeText(this, "Notifications disabled", Toast.LENGTH_SHORT).show()
        }
    }

    // Add helper function to check if service is running
    private fun isServiceRunning(): Boolean {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.activeNotifications.any { it.id == NotificationService.NOTIFICATION_ID }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition {
            viewModel.uiState.value is Result.Loading && !viewModel.hasInitialData
        }

        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Initialize NetworkClient and SettingsRepository
        NetworkClient.initialize(applicationContext)
        settingsRepository = SettingsRepository.getInstance(applicationContext)

        // Fix: Move the NetworkClient initialization check into a proper coroutine scope
        lifecycleScope.launch {
            NetworkClient.isInitialized.collect { isInitialized ->
                if (isInitialized) {
                    viewModel.refreshData()
                }
            }
        }

        // Restore notification service state
        lifecycleScope.launch {
            settingsRepository.settings.collect { settings ->
                if (settings.isServiceEnabled) {
                    val serviceIntent = Intent(this@MainActivity, NotificationService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Check service state and update settings immediately
        updateServiceState()

        setContent {
            MempalTheme {
                MainScreen(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Update service state on resume
        updateServiceState()

        // Existing onResume code
        if (!NetworkClient.isInitialized.value) {
            NetworkClient.initialize(applicationContext)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources
        if (isFinishing) {
            // Only cleanup if activity is actually being destroyed, not recreated
            NetworkClient.cleanup()
            SettingsRepository.cleanup()
            requestPermissionLauncher.unregister()
        }
    }

    private fun updateServiceState() {
        lifecycleScope.launch {
            val isRunning = isServiceRunning()
            val currentSettings = settingsRepository.settings.value

            if (isRunning && !currentSettings.isServiceEnabled) {
                // Service is running but settings show it's disabled
                settingsRepository.updateSettings(currentSettings.copy(isServiceEnabled = true))
            } else if (!isRunning && currentSettings.isServiceEnabled) {
                // Service should be running but isn't
                val serviceIntent = Intent(this@MainActivity, NotificationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
        }
    }
}

@Composable
private fun MainScreen(viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val uiState by viewModel.uiState.collectAsState()
    val isInitialized by NetworkClient.isInitialized.collectAsState()
    val context = LocalContext.current

    // Effect to handle periodic refresh
    LaunchedEffect(selectedTab, isInitialized) {
        if (selectedTab == 0) {
            while (true) {
                if (isInitialized) {
                    viewModel.refreshData()
                } else {
                    // If NetworkClient is not initialized, try to re-initialize
                    NetworkClient.initialize(context.applicationContext)
                }
                delay(30000) // 30 seconds delay between refreshes
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Scaffold(
            modifier = Modifier.windowInsetsPadding(
                WindowInsets.systemBars.only(WindowInsetsSides.Top)
            ),
            topBar = {
                if (selectedTab == 0) {
                    AppHeader(
                        onRefresh = {
                            if (isInitialized) {
                                viewModel.refreshData()
                            } else {
                                NetworkClient.initialize(context.applicationContext)
                            }
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 0.dp, horizontal = 4.dp)
                            .height(150.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = "App Logo",
                            modifier = Modifier
                                .size(300.dp)
                                .align(Alignment.Center)
                        )
                    }
                }
            },
            bottomBar = {
                Column {
                    HorizontalDivider(
                        color = AppColors.DataGray.copy(alpha = 0.2f),
                        thickness = 0.9.dp
                    )
                    NavigationBar(
                        containerColor = AppColors.DarkerNavy
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                            label = { Text("Dashboard") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppColors.Orange,
                                selectedTextColor = AppColors.Orange,
                                indicatorColor = AppColors.DarkerNavy,
                                unselectedIconColor = AppColors.DataGray,
                                unselectedTextColor = AppColors.DataGray
                            )
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                            label = { Text("Notifications") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppColors.Orange,
                                selectedTextColor = AppColors.Orange,
                                indicatorColor = AppColors.DarkerNavy,
                                unselectedIconColor = AppColors.DataGray,
                                unselectedTextColor = AppColors.DataGray
                            )
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { Text("Settings") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppColors.Orange,
                                selectedTextColor = AppColors.Orange,
                                indicatorColor = AppColors.DarkerNavy,
                                unselectedIconColor = AppColors.DataGray,
                                unselectedTextColor = AppColors.DataGray
                            )
                        )
                    }
                }
            }
        ) { paddingValues ->
            when (selectedTab) {
                0 -> MainContent(
                    viewModel = viewModel,
                    uiState = uiState,
                    modifier = Modifier.padding(paddingValues)
                )
                1 -> NotificationsScreen(modifier = Modifier.padding(paddingValues))
                2 -> SettingsScreen(modifier = Modifier.padding(paddingValues))
            }
        }
    }
}

@Composable
private fun AppHeader(onRefresh: () -> Unit) {
    var isRotating by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (isRotating) 360f else 0f,
        animationSpec = tween(
            durationMillis = 500,
            easing = FastOutSlowInEasing
        ),
        finishedListener = { isRotating = false },
        label = ""
    )

    val torManager = remember { TorManager.getInstance() }
    val torStatus by torManager.torStatus.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.dp, horizontal = 4.dp)
            .height(150.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.app_logo),
            contentDescription = "App Logo",
            modifier = Modifier
                .size(300.dp)
                .align(Alignment.Center)
        )

        // Tor Status Indicator
        if (torStatus == TorStatus.CONNECTED) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 26.5.dp, bottom = 18.5.dp)
            ) {
                var showTooltip by remember { mutableStateOf(false) }

                IconButton(
                    onClick = { showTooltip = !showTooltip },
                    modifier = Modifier.size(24.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_onion),
                        contentDescription = "Tor Connected",
                        modifier = Modifier.size(24.dp)
                    )
                }

                if (showTooltip) {
                    Popup(
                        onDismissRequest = { showTooltip = false },
                        alignment = Alignment.TopStart
                    ) {
                        Surface(
                            modifier = Modifier.padding(8.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = AppColors.DarkGray,
                            tonalElevation = 4.dp
                        ) {
                            Text(
                                text = "Tor Connected",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        IconButton(
            onClick = {
                isRotating = true
                onRefresh()
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 8.dp)
                .size(44.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh",
                tint = Color.White,
                modifier = Modifier
                    .size(32.dp)
                    .graphicsLayer {
                        rotationZ = rotation
                    }
            )
        }
    }
}

@Composable
private fun MainContent(
    viewModel: MainViewModel,
    uiState: Result<Unit>,
    modifier: Modifier = Modifier
) {
    val blockHeight by viewModel.blockHeight.observeAsState()
    val blockTimestamp by viewModel.blockTimestamp.observeAsState()
    val feeRates by viewModel.feeRates.observeAsState()
    val mempoolInfo by viewModel.mempoolInfo.observeAsState()

    when (uiState) {
        is Result.Error -> {
            if (!viewModel.hasInitialData) {
                // Show loading cards if we haven't received any data yet
                MainContentDisplay(
                    blockHeight = null,
                    blockTimestamp = null,
                    feeRates = null,
                    mempoolInfo = null,
                    modifier = modifier
                )
            } else {
                // Show error only if we had data before
                ErrorDisplay(
                    message = uiState.message,
                    onRetry = viewModel::refreshData,
                    modifier = modifier
                )
            }
        }
        else -> MainContentDisplay(
            blockHeight = blockHeight,
            blockTimestamp = blockTimestamp,
            feeRates = feeRates,
            mempoolInfo = mempoolInfo,
            modifier = modifier
        )
    }
}

@Composable
private fun ErrorDisplay(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

@Composable
private fun MainContentDisplay(
    blockHeight: Int?,
    blockTimestamp: Long?,
    feeRates: FeeRates?,
    mempoolInfo: MempoolInfo?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DataCard(
            title = "Current Block Height",
            icon = Icons.Default.Numbers,
            content = blockHeight?.let {
                {
                    Column {
                        Text(
                            text = String.format(Locale.US, "%,d", it),
                            style = MaterialTheme.typography.headlineLarge,
                            color = AppColors.DataGray
                        )
                        blockTimestamp?.let { timestamp ->
                            val elapsedMinutes = (System.currentTimeMillis() / 1000 - timestamp) / 60
                            Text(
                                text = "(${elapsedMinutes} minutes ago)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.DataGray.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            },
            isLoading = blockHeight == null
        )

        DataCard(
            title = "Mempool Size",
            icon = Icons.Default.Speed,
            content = mempoolInfo?.let {
                {
                    Column {
                        Text(
                            text = String.format(Locale.US, "%.2f vMB", it.vsize / 1_000_000.0),
                            style = MaterialTheme.typography.headlineLarge,
                            color = AppColors.DataGray
                        )
                        val blocksToClean = ceil(it.vsize / 1_000_000.0 / 1.5).toInt()
                        Text(
                            text = "(${blocksToClean} blocks to clear)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.DataGray.copy(alpha = 0.7f)
                        )
                    }
                }
            },
            isLoading = mempoolInfo == null
        )

        DataCard(
            title = "Fee Rates (sat/vB)",
            content = if (feeRates != null) { { FeeRatesContent(feeRates) } } else null,
            icon = Icons.Default.CurrencyBitcoin,
            tooltip = "This section shows a rough average of recommended fees with varying confirmation times." +
                    "\n\n*Note: At times a flood of transactions may enter the mempool and drastically push up fee rates. " +
                    "These floods are often temporary with only a few vMB worth of transactions that clear relatively quickly. " +
                    "In this scenario be sure to check the 'Fee Distribution' table to see how big the flood is and how quickly it will clear " +
                    "to ensure you do not overpay fees.",
        )

        DataCard(
            title = "Fee Distribution",
            content = if (mempoolInfo != null) { { HistogramContent(mempoolInfo) } } else null,
            icon = Icons.Default.BarChart,
            tooltip = "Fee Distribution shows a detailed breakdown of the mempool, giving you more insight into choosing the correct fee rate. " +
                    "\n\nRange Key:" +
                    "\n-Green will be confirmed in the next block." +
                    "\n-Yellow might be confirmed in the next block." +
                    "\n-Red will not be confirmed in the next block." +
                    "\n\n*Note: Each Bitcoin block confirms about 1.5vMB worth of transactions.",
        )
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
    isLoading: Boolean = value == null && content == null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = AppColors.DarkerNavy
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                if (tooltip != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    TooltipButton(tooltip = tooltip)
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = AppColors.Orange,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            } else {
                if (value != null) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.headlineMedium,
                        color = AppColors.DataGray
                    )
                } else if (content != null) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun TooltipButton(tooltip: String) {
    var showTooltip by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { showTooltip = !showTooltip },
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Info",
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurface
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
                    Text(
                        text = tooltip,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun FeeRatesContent(feeRates: FeeRates) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FeeRateRow("Next Block", feeRates.fastestFee)
        FeeRateRow("In 3 Blocks", feeRates.halfHourFee)
        FeeRateRow("In 6 Blocks", feeRates.hourFee)
        FeeRateRow("In 1 Day", feeRates.economyFee)
    }
}

@Composable
private fun FeeRateRow(
    label: String,
    value: Int?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (value != null) "$value" else "...",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun HistogramContent(mempoolInfo: MempoolInfo) {
    if (mempoolInfo.feeHistogram.isNotEmpty()) {
        // Process histogram data first
        val sizeMap = mutableMapOf<String, Double>()
        var totalSize = 0.0

        // Process the raw data first
        mempoolInfo.feeHistogram.forEach { feeRange ->
            if (feeRange.size >= 2) {
                val fee = feeRange[0].toInt()
                val size = feeRange[1]

                // Generate range string based on fee value
                val rangeStr = when {
                    fee >= 2000 -> "2000+"
                    fee >= 1800 -> "1800 - 2000"
                    fee >= 1600 -> "1600 - 1800"
                    fee >= 1400 -> "1400 - 1600"
                    fee >= 1200 -> "1200 - 1400"
                    fee >= 1000 -> "1000 - 1200"
                    fee >= 900 -> "900 - 1000"
                    fee >= 800 -> "800 - 900"
                    fee >= 700 -> "700 - 800"
                    fee >= 600 -> "600 - 700"
                    fee >= 500 -> "500 - 600"
                    fee >= 450 -> "450 - 500"
                    fee >= 400 -> "400 - 450"
                    fee >= 350 -> "350 - 400"
                    fee >= 300 -> "300 - 350"
                    fee >= 275 -> "275 - 300"
                    fee >= 250 -> "250 - 275"
                    fee >= 225 -> "225 - 250"
                    fee >= 200 -> "200 - 225"
                    fee >= 180 -> "180 - 200"
                    fee >= 160 -> "160 - 180"
                    fee >= 140 -> "140 - 160"
                    fee >= 120 -> "120 - 140"
                    fee >= 100 -> "100 - 120"
                    fee >= 90 -> "90 - 100"
                    fee >= 80 -> "80 - 90"
                    fee >= 70 -> "70 - 80"
                    fee >= 60 -> "60 - 70"
                    fee >= 55 -> "55 - 60"
                    fee >= 50 -> "50 - 55"
                    fee >= 45 -> "45 - 50"
                    fee >= 40 -> "40 - 45"
                    fee >= 38 -> "38 - 40"
                    fee >= 36 -> "36 - 38"
                    fee >= 34 -> "34 - 36"
                    fee >= 32 -> "32 - 34"
                    fee >= 30 -> "30 - 32"
                    fee >= 28 -> "28 - 30"
                    fee >= 26 -> "26 - 28"
                    fee >= 24 -> "24 - 26"
                    fee >= 22 -> "22 - 24"
                    fee >= 20 -> "20 - 22"
                    fee >= 19 -> "19 - 20"
                    fee >= 18 -> "18 - 19"
                    fee >= 17 -> "17 - 18"
                    fee >= 16 -> "16 - 17"
                    fee >= 15 -> "15 - 16"
                    fee >= 14 -> "14 - 15"
                    fee >= 13 -> "13 - 14"
                    fee >= 12 -> "12 - 13"
                    fee >= 11 -> "11 - 12"
                    fee >= 10 -> "10 - 11"
                    fee >= 9 -> "9 - 10"
                    fee >= 8 -> "8 - 9"
                    fee >= 7 -> "7 - 8"
                    fee >= 6 -> "6 - 7"
                    fee >= 5 -> "5 - 6"
                    fee >= 4 -> "4 - 5"
                    fee >= 3 -> "3 - 4"
                    fee >= 2 -> "2 - 3"
                    else -> "1 - 2"
                }

                sizeMap[rangeStr] = (sizeMap[rangeStr] ?: 0.0) + size
                totalSize += size
            }
        }

        // Filter out zero entries and sort by fee range
        val nonZeroEntries = sizeMap.entries
            .filter { it.value > 0 }
            .sortedByDescending { entry ->
                when {
                    entry.key.endsWith("+") -> entry.key.removeSuffix("+").toInt()
                    else -> entry.key.split(" - ").first().toInt()
                }
            }

        // Calculate running sum
        var runningSum = 0.0
        val entriesWithSum = nonZeroEntries.map { entry ->
            runningSum += entry.value
            Triple(entry.key, entry.value, runningSum)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Header row
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

            // Show entries with running sum
            entriesWithSum.forEach { (range, _, sum) ->
                val sumInMB = sum / 1_000_000
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = range,
                        style = MaterialTheme.typography.titleLarge,
                        color = when {
                            sumInMB > 1.5 -> AppColors.WarningRed
                            sumInMB > 1.2 -> Color(0xFFFFA500) // Orange/Yellow color
                            else -> Color(0xFF4CAF50) // Green color
                        },
                        fontFamily = FontFamily.Default
                    )
                    Text(
                        text = String.format(Locale.US, "%.2f vMB", sumInMB),
                        style = MaterialTheme.typography.titleLarge,
                        color = when {
                            sumInMB > 1.5 -> AppColors.WarningRed
                            sumInMB > 1.2 -> Color(0xFFFFA500) // Orange/Yellow color
                            else -> Color(0xFF4CAF50) // Green color
                        },
                        fontFamily = FontFamily.Default
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )
        }
    } else {
        Text("Loading...", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun NotificationsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val settings by settingsRepository.settings.collectAsState(NotificationSettings())

    // Check if any notification type is enabled
    val isAnyNotificationEnabled = settings.run {
        blockNotificationsEnabled && (newBlockNotificationEnabled || specificBlockNotificationEnabled) ||
                mempoolSizeNotificationsEnabled ||
                feeRatesNotificationsEnabled ||
                (txConfirmationEnabled && transactionId.isNotEmpty())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "Notifications",
            style = MaterialTheme.typography.headlineMedium,
            color = AppColors.Orange
        )

        Button(
            onClick = {
                val serviceIntent = Intent(context, NotificationService::class.java)
                if (!settings.isServiceEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    settingsRepository.updateSettings(settings.copy(isServiceEnabled = true))
                } else {
                    context.stopService(serviceIntent)
                    settingsRepository.updateSettings(settings.copy(isServiceEnabled = false))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = settings.isServiceEnabled || isAnyNotificationEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (settings.isServiceEnabled) AppColors.WarningRed else AppColors.Orange,
                disabledContainerColor = AppColors.Orange.copy(alpha = 0.5f)
            )
        ) {
            Text(
                text = if (settings.isServiceEnabled)"Stop Notification Service" else "Start Notification Service",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }

        // Bitcoin Blocks section
        NotificationSection(
            config = NotificationSectionConfig(
                title = stringResource(R.string.blocks_title),
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

        // Fee Rates section
        FeeRatesNotificationSection(
            enabled = settings.feeRatesNotificationsEnabled,
            frequency = settings.feeRatesCheckFrequency,
            selectedFeeRateType = settings.selectedFeeRateType,
            threshold = settings.feeRateThreshold,
            isAboveThreshold = settings.feeRateAboveThreshold,
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

        // Transaction Confirmation section
        TransactionConfirmationSection(
            enabled = settings.txConfirmationEnabled,
            frequency = settings.txConfirmationFrequency,
            transactionId = settings.transactionId,
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

        // Mempool Size section (moved to bottom)
        MempoolSizeNotificationSection(
            enabled = settings.mempoolSizeNotificationsEnabled,
            frequency = settings.mempoolCheckFrequency,
            threshold = settings.mempoolSizeThreshold,
            aboveThreshold = settings.mempoolSizeAboveThreshold,
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
    newBlockConfig: NewBlockConfig? = null,
    specificBlockConfig: SpecificBlockConfig? = null
) {
    var debouncedBlockHeight by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = AppColors.DarkerNavy
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Main header with toggle
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
                    onCheckedChange = config.onEnabledChange
                )
            }
            Text(
                text = config.description,
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.DataGray
            )

            if (config.enabled && newBlockConfig != null) {
                // New Block Notifications Sub-section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp),
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
                                uncheckedThumbColor = AppColors.DataGray,
                                uncheckedTrackColor = AppColors.DarkerNavy,
                                uncheckedBorderColor = Color.Gray
                            )
                        )
                    }

                    if (newBlockConfig.enabled) {
                        OutlinedTextField(
                            value = newBlockConfig.frequency.toString(),
                            onValueChange = {
                                it.toIntOrNull()?.let { value ->
                                    if (value > 0) newBlockConfig.onFrequencyChange(value)
                                }
                            },
                            label = { Text("Check Frequency (minutes)", color = AppColors.DataGray) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = AppColors.DataGray,
                                focusedBorderColor = AppColors.Orange,
                                unfocusedTextColor = AppColors.DataGray,
                                focusedTextColor = AppColors.Orange
                            )
                        )
                    }
                }

                // Specific Block Height Section
                if (specificBlockConfig != null) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = AppColors.DataGray.copy(alpha = 0.5f)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp),
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
                                    uncheckedThumbColor = AppColors.DataGray,
                                    uncheckedTrackColor = AppColors.DarkerNavy,
                                    uncheckedBorderColor = Color.Gray
                                )
                            )
                        }

                        if (specificBlockConfig.enabled) {
                            OutlinedTextField(
                                value = debouncedBlockHeight,
                                onValueChange = { newValue ->
                                    debouncedBlockHeight = newValue
                                    if (newValue.isNotEmpty()) {
                                        newValue.toIntOrNull()?.let { value ->
                                            if (value > 0) {
                                                debounceJob?.cancel()
                                                debounceJob = scope.launch {
                                                    delay(4000) // 4 second debounce
                                                    specificBlockConfig.onTargetHeightChange(value)
                                                }
                                            }
                                        }
                                    }
                                },
                                label = { Text("Target Block Height", color = AppColors.DataGray) },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = AppColors.DataGray,
                                    focusedBorderColor = AppColors.Orange,
                                    unfocusedTextColor = AppColors.DataGray,
                                    focusedTextColor = AppColors.Orange
                                )
                            )

                            OutlinedTextField(
                                value = specificBlockConfig.frequency.toString(),
                                onValueChange = {
                                    it.toIntOrNull()?.let { value ->
                                        if (value > 0) specificBlockConfig.onFrequencyChange(value)
                                    }
                                },
                                label = { Text("Check Frequency (minutes)", color = AppColors.DataGray) },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = AppColors.DataGray,
                                    focusedBorderColor = AppColors.Orange,
                                    unfocusedTextColor = AppColors.DataGray,
                                    focusedTextColor = AppColors.Orange
                                )
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
    onEnabledChange: (Boolean) -> Unit,
    onFrequencyChange: (Int) -> Unit,
    onThresholdChange: (Float) -> Unit,
    onAboveThresholdChange: (Boolean) -> Unit
) {
    var debouncedThreshold by remember { mutableFloatStateOf(threshold) }
    val scope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = AppColors.DarkerNavy
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                    onCheckedChange = onEnabledChange
                )
            }
            Text(
                text = "Get notified when mempool size ${if (aboveThreshold) "rises above" else "falls below"} threshold.",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.DataGray
            )
            if (enabled) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ThresholdToggle(
                        isAboveThreshold = aboveThreshold,
                        onToggleChange = onAboveThresholdChange
                    )
                    OutlinedTextField(
                        value = debouncedThreshold.toString(),
                        onValueChange = { newValue ->
                            newValue.toFloatOrNull()?.let { value ->
                                if (value > 0) {
                                    debouncedThreshold = value
                                    debounceJob?.cancel()
                                    debounceJob = scope.launch {
                                        delay(1000) // 1 second debounce
                                        onThresholdChange(value)
                                    }
                                }
                            }
                        },
                        label = { Text("Threshold (vMB)", color = AppColors.DataGray) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = AppColors.DataGray,
                            focusedBorderColor = AppColors.Orange,
                            unfocusedTextColor = AppColors.DataGray,
                            focusedTextColor = AppColors.Orange
                        )
                    )
                    OutlinedTextField(
                        value = frequency.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { value ->
                                if (value > 0) onFrequencyChange(value)
                            }
                        },
                        label = { Text("Check Frequency (minutes)", color = AppColors.DataGray) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = AppColors.DataGray,
                            focusedBorderColor = AppColors.Orange,
                            unfocusedTextColor = AppColors.DataGray,
                            focusedTextColor = AppColors.Orange
                        )
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
    threshold: Int,
    isAboveThreshold: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onFrequencyChange: (Int) -> Unit,
    onFeeRateTypeChange: (FeeRateType) -> Unit,
    onThresholdChange: (Int) -> Unit,
    onAboveThresholdChange: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var debouncedThreshold by remember { mutableIntStateOf(threshold) }
    val scope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = AppColors.DarkerNavy
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                    onCheckedChange = onEnabledChange
                )
            }
            Text(
                text = "Get notified when fee rates ${if (isAboveThreshold) "rise above" else "fall below"} threshold.",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.DataGray
            )
            if (enabled) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ThresholdToggle(
                        isAboveThreshold = isAboveThreshold,
                        onToggleChange = onAboveThresholdChange
                    )

                    OutlinedTextField(
                        value = debouncedThreshold.toString(),
                        onValueChange = { newValue ->
                            newValue.toIntOrNull()?.let { value ->
                                if (value > 0) {
                                    debouncedThreshold = value
                                    debounceJob?.cancel()
                                    debounceJob = scope.launch {
                                        delay(1000) // 1 second debounce
                                        onThresholdChange(value)
                                    }
                                }
                            }
                        },
                        label = { Text("Threshold (sat/vB)", color = AppColors.DataGray) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = AppColors.DataGray,
                            focusedBorderColor = AppColors.Orange,
                            unfocusedTextColor = AppColors.DataGray,
                            focusedTextColor = AppColors.Orange
                        )
                    )

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = when (selectedFeeRateType) {
                                FeeRateType.NEXT_BLOCK -> "Next Block"
                                FeeRateType.TWO_BLOCKS -> "3 Blocks"
                                FeeRateType.FOUR_BLOCKS -> "6 Blocks"
                                FeeRateType.DAY_BLOCKS -> "1 Day"
                            },
                            label = { Text("Fee Rate", color = AppColors.DataGray) },
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
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
                                    onFeeRateTypeChange(FeeRateType.TWO_BLOCKS)
                                    expanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("6 Blocks") },
                                onClick = {
                                    onFeeRateTypeChange(FeeRateType.FOUR_BLOCKS)
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

                    OutlinedTextField(
                        value = frequency.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { value ->
                                if (value > 0) onFrequencyChange(value)
                            }
                        },
                        label = { Text("Check Frequency (minutes)", color = AppColors.DataGray) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = AppColors.DataGray,
                            focusedBorderColor = AppColors.Orange,
                            unfocusedTextColor = AppColors.DataGray,
                            focusedTextColor = AppColors.Orange
                        )
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
    onEnabledChange: (Boolean) -> Unit,
    onFrequencyChange: (Int) -> Unit,
    onTransactionIdChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = AppColors.DarkerNavy
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Confirmation",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AppColors.Orange
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    TooltipButton(
                        tooltip = "Caution: This feature has privacy implications.\nIf you're concerned about privacy, be sure to use the " +
                                "'Enable Tor' option in settings or connect to your own custom mempool server.",
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }
            Text(
                text = "Get notified when your transaction is confirmed.",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.DataGray
            )
            if (enabled) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedTextField(
                        value = transactionId,
                        onValueChange = onTransactionIdChange,
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
                    OutlinedTextField(
                        value = frequency.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { value ->
                                if (value > 0) onFrequencyChange(value)
                            }
                        },
                        label = { Text("Check Frequency (minutes)", color = AppColors.DataGray) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = AppColors.DataGray,
                            focusedBorderColor = AppColors.Orange,
                            unfocusedTextColor = AppColors.DataGray,
                            focusedTextColor = AppColors.Orange
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val torManager = remember { TorManager.getInstance() }
    val torStatus by torManager.torStatus.collectAsState()
    var torEnabled by remember { mutableStateOf(torManager.isTorEnabled()) }
    var updateFrequency by remember { mutableLongStateOf(settingsRepository.getUpdateFrequency()) }

    var selectedOption by remember {
        mutableIntStateOf(
            if (settingsRepository.getApiUrl() == "https://mempool.space") 0 else 1
        )
    }
    var customUrl by remember { mutableStateOf(
        if (settingsRepository.getApiUrl() != "https://mempool.space")
            settingsRepository.getApiUrl() else ""
    ) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var showUrlError by remember { mutableStateOf(false) }

    // URL validation function
    fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }

    // Update the Button onClick handler
    fun handleSave() {
        val newUrl = if (selectedOption == 0) {
            // When saving with default option, ensure Tor is disabled
            if (torManager.isTorEnabled()) {
                torManager.stopTor(context)
                torEnabled = false
            }
            "https://mempool.space"
        } else {
            customUrl.trim()
        }

        if (selectedOption == 1) {
            if (!isValidUrl(newUrl)) {
                showUrlError = true
                return
            }
            // Disable Tor if saving a non-onion address
            if (!newUrl.contains(".onion") && torManager.isTorEnabled()) {
                torManager.stopTor(context)
                torEnabled = false
            }
        }

        showUrlError = false
        settingsRepository.saveApiUrl(newUrl)
        showRestartDialog = true
    }

    // Add this function to handle server testing
    val scope = rememberCoroutineScope()
    var isTestingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }

    suspend fun testServerConnection(url: String): Boolean {
        return try {
            if (url.contains(".onion")) {
                // Check Tor status first
                if (torManager.torStatus.value != TorStatus.CONNECTED) {
                    return false
                }
                val client = NetworkClient.createTestClient(url, useTor = true)
                val response = client.getBlockHeight()
                response.isSuccessful
            } else {
                val client = NetworkClient.createTestClient(url, useTor = false)
                val response = client.getBlockHeight()
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = AppColors.Orange
        )

        // Mempool Server Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = AppColors.DarkerNavy
            )
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

                // Server selection options
                Column {
                    RadioOption(
                        text = "Default (mempool.space)",
                        selected = selectedOption == 0,
                        onClick = {
                            selectedOption = 0
                            customUrl = ""
                            if (torManager.isTorEnabled()) {
                                torManager.stopTor(context)
                                torEnabled = false
                            }
                        }
                    )

                    RadioOption(
                        text = "Custom Server",
                        selected = selectedOption == 1,
                        onClick = { selectedOption = 1 }
                    )

                    if (selectedOption == 1) {
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = {
                                customUrl = it
                                showUrlError = false
                                testResult = null
                                if (it.contains(".onion")) {
                                    if (!torEnabled) {
                                        torEnabled = true
                                        torManager.startTor(context)
                                    }
                                }
                            },
                            label = { Text(if (torEnabled) "Onion Address" else "Address", color = AppColors.DataGray) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AppColors.Orange,
                                unfocusedBorderColor = AppColors.DataGray
                            ),
                            isError = showUrlError
                        )

                        if (showUrlError) {
                            Text(
                                text = "URL must start with http:// or https://",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                            )
                        }

                        // Test Server button and result
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            Button(
                                onClick = {
                                    isTestingConnection = true
                                    scope.launch {
                                        testResult = testServerConnection(customUrl)
                                        isTestingConnection = false
                                    }
                                },
                                enabled = !isTestingConnection && customUrl.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppColors.Orange,
                                    disabledContainerColor = AppColors.Orange.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isTestingConnection) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("Test Server")
                                }
                            }

                            if (testResult != null && !isTestingConnection && customUrl.isNotEmpty()) {
                                Text(
                                    text = if (testResult == true) "Connection successful" else "Connection failed",
                                    color = if (testResult == true) Color.Green else Color.Red,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .padding(top = 4.dp)
                                        .fillMaxWidth(),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    // Tor controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Enable Tor",
                                style = MaterialTheme.typography.bodyLarge,
                                color = AppColors.DataGray
                            )
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
                            if (torEnabled) {
                                Text(
                                    text = "Tor defaults to mempool.space's onion address.",
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
                                    if (customUrl.isEmpty()) {
                                        customUrl = "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion/"
                                    }
                                } else {
                                    torManager.stopTor(context)
                                    customUrl = ""
                                }
                            },
                            enabled = torStatus != TorStatus.CONNECTING,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = AppColors.Orange,
                                uncheckedThumbColor = AppColors.DataGray,
                                uncheckedTrackColor = AppColors.DarkerNavy,
                                checkedBorderColor = Color.Gray,
                                uncheckedBorderColor = Color.Gray
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { handleSave() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.Orange
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save")
                    }
                }
            }
        }

        // Widget Settings Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = AppColors.DarkerNavy
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Widgets",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AppColors.Orange
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    TooltipButton(
                        tooltip = "Widgets can be manually updated by tapping them once. " +
                                "You can also double tap any widget to open the Mempal app."
                    )
                }

                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = when (updateFrequency) {
                            60L -> "1 hour"
                            180L -> "3 hours"
                            360L -> "6 hours"
                            else -> "$updateFrequency minutes"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Update Frequency") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                            .padding(top = 16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.Orange,
                            unfocusedBorderColor = AppColors.DataGray,
                            focusedLabelColor = AppColors.Orange
                        )
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        listOf(15L, 30L, 60L, 180L).forEach { minutes ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (minutes) {
                                            60L -> "1 hour"
                                            180L -> "3 hours"
                                            else -> "$minutes minutes"
                                        }
                                    )
                                },
                                onClick = {
                                    updateFrequency = minutes
                                    settingsRepository.saveUpdateFrequency(minutes)
                                    WidgetUpdater.scheduleUpdates(context)
                                    expanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }

                Text(
                    text = when (updateFrequency) {
                        60L -> "Widgets will update every hour."
                        180L -> "Widgets will update every 3 hours."
                        else -> "Widgets will update every $updateFrequency minutes."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.DataGray,
                    modifier = Modifier.padding(top = 4.dp, start = 16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Footer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = android.net.Uri.parse("https://github.com/aeonBTC/Mempal/")
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
            onDismissRequest = { showRestartDialog = false },
            title = {
                Text("Restart Required")
            },
            text = {
                Text("Please restart the app to save your settings.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestartDialog = false
                        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        (context as? Activity)?.finishAffinity()
                        Runtime.getRuntime().exit(0)
                    }
                ) {
                    Text("Restart Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) {
                    Text("Later")
                }
            }
        )
    }
}

@Composable
private fun RadioOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .selectable(selected = selected, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = AppColors.Orange
            ),
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
            text = "Notify when",
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