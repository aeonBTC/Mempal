package com.example.mempal

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.HorizontalDivider
import com.example.mempal.api.FeeRates
import com.example.mempal.api.MempoolInfo
import com.example.mempal.api.Result
import com.example.mempal.ui.theme.MempalTheme
import com.example.mempal.viewmodel.MainViewModel
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.window.Popup
import java.util.Locale
import androidx.compose.ui.graphics.Color
import com.example.mempal.ui.theme.AppColors
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import com.example.mempal.service.NotificationService
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import com.example.mempal.model.NotificationSettings
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import com.example.mempal.model.FeeRateType
import com.example.mempal.repository.SettingsRepository
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import android.app.NotificationManager
import android.content.Context

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition {
            viewModel.uiState.value is Result.Loading && !viewModel.hasInitialData
        }

        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        viewModel.refreshData()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        settingsRepository = SettingsRepository(this)

        setContent {
            MempalTheme {
                MainScreen(viewModel)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                // Permission granted, proceed with notifications
            } else {
                // Permission denied, handle accordingly
            }
        }
    }
}

@Composable
private fun MainScreen(viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(selectedTab) {
        if (selectedTab == 0) {
            viewModel.refreshData()
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
                    AppHeader(onRefresh = viewModel::refreshData)
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
    val feeRates by viewModel.feeRates.observeAsState()
    val mempoolInfo by viewModel.mempoolInfo.observeAsState()

    when (uiState) {
        is Result.Error -> ErrorDisplay(
            message = uiState.message,
            onRetry = viewModel::refreshData,
            modifier = modifier
        )
        else -> MainContentDisplay(
            blockHeight = blockHeight,
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
            value = blockHeight?.let {
                String.format(Locale.US, "%,d", it)
            },
            icon = Icons.Default.Numbers
        )
        DataCard(
            title = "Mempool Size",
            value = mempoolInfo?.let {
                String.format(Locale.US, "%.2f vMB", it.vsize / 1_000_000.0)
            },
            icon = Icons.Default.Speed
        )

        DataCard(
            title = "Fee Rates (sat/vB)",
            content = { FeeRatesContent(feeRates) },
            icon = Icons.Default.CurrencyBitcoin,
            tooltip = "This section shows a rough average of recommended fees with varying confirmation times." +
                    "\n\n*At times, a flood of transactions may enter the mempool and drastically push up fee rates. " +
                    "These floods are often temporary with only a few vMB worth of transactions that clear relatively quickly. " +
                    "In this scenario be sure to check the Fee Distribution table to see how big the flood is and how quickly it will clear " +
                    "to ensure you do not overpay fees."
        )

        DataCard(
            title = "Fee Distribution",
            content = { HistogramContent(mempoolInfo) },
            icon = Icons.Default.BarChart,
            tooltip = "Fee Distribution shows a detailed breakdown of the mempool, giving you more insight into choosing the correct fee rate. " +
                    "\n\n-Green will be confirmed in the next block." +
                    "\n-Yellow might be confirmed in the next block." +
                    "\n-Red will not be confirmed in the next block." +
                    "\n\n*Each Bitcoin block confirms about 1.5vMB worth of transactions."
        )
    }
}

@Composable
private fun DataCard(
    title: String,
    value: String? = null,
    content: @Composable (() -> Unit)? = null,
    icon: ImageVector,
    tooltip: String? = null,
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (tooltip != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        TooltipButton(tooltip = tooltip)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                content?.invoke()
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
private fun FeeRatesContent(feeRates: FeeRates?) {
    if (feeRates != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FeeRateRow("Next Block", feeRates.fastestFee)
            FeeRateRow("In 2 Blocks", feeRates.halfHourFee)
            FeeRateRow("In 4 Blocks", feeRates.hourFee)
            FeeRateRow("In 1 Day", feeRates.economyFee)
        }
    } else {
        Text("Loading...", style = MaterialTheme.typography.bodyLarge)
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
private fun HistogramContent(mempoolInfo: MempoolInfo?) {
    if (mempoolInfo != null && mempoolInfo.feeHistogram.isNotEmpty()) {
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

    // Function to check if service is running
    fun isServiceRunning(): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.activeNotifications.any { it.id == 1 }
    }

    // Use LaunchedEffect to update service state when screen is loaded
    var isServiceRunning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isServiceRunning = isServiceRunning()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "Notification Settings",
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
            colors = ButtonDefaults.buttonColors(
                containerColor = if (settings.isServiceEnabled) AppColors.WarningRed else AppColors.Orange
            )
        ) {
            Text(
                text = if (settings.isServiceEnabled) "Disable Notification Service" else "Enable Notification Service",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }

        // Notification sections with updated styling
        NotificationSection(
            title = "Bitcoin Blocks",
            description = "Get notified when new blocks are mined.",
            enabled = settings.blockNotificationsEnabled,
            frequency = settings.blockCheckFrequency,
            onEnabledChange = { newSettings ->
                settingsRepository.updateSettings(settings.copy(blockNotificationsEnabled = newSettings))
            },
            onFrequencyChange = { newSettings ->
                settingsRepository.updateSettings(settings.copy(blockCheckFrequency = newSettings))
            }
        )

        MempoolSizeNotificationSection(
            enabled = settings.mempoolSizeNotificationsEnabled,
            frequency = settings.mempoolCheckFrequency,
            threshold = settings.mempoolSizeThreshold,
            onEnabledChange = { newSettings ->
                settingsRepository.updateSettings(settings.copy(mempoolSizeNotificationsEnabled = newSettings))
            },
            onFrequencyChange = { newSettings ->
                settingsRepository.updateSettings(settings.copy(mempoolCheckFrequency = newSettings))
            },
            onThresholdChange = { newSettings ->
                settingsRepository.updateSettings(settings.copy(mempoolSizeThreshold = newSettings))
            }
        )

        FeeRatesNotificationSection(
            enabled = settings.feeRatesNotificationsEnabled,
            frequency = settings.feeRatesCheckFrequency,
            selectedFeeRateType = settings.selectedFeeRateType,
            threshold = settings.feeRateThreshold,
            onEnabledChange = { newSettings ->
                settingsRepository.updateSettings(settings.copy(feeRatesNotificationsEnabled = newSettings))
            },
            onFrequencyChange = { newSettings ->
                settingsRepository.updateSettings(settings.copy(feeRatesCheckFrequency = newSettings))
            },
            onFeeRateTypeChange = { newSettings ->
                settingsRepository.updateSettings(settings.copy(selectedFeeRateType = newSettings))
            },
            onThresholdChange = { newSettings ->
                settingsRepository.updateSettings(settings.copy(feeRateThreshold = newSettings))
            }
        )
    }
}

@Composable
private fun NotificationSection(
    title: String,
    description: String,
    enabled: Boolean,
    frequency: Int,
    onEnabledChange: (Boolean) -> Unit,
    onFrequencyChange: (Int) -> Unit
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppColors.Orange
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.DataGray
            )
            if (enabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Check every",
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.DataGray
                    )
                    OutlinedTextField(
                        value = frequency.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { value ->
                                if (value > 0) onFrequencyChange(value)
                            }
                        },
                        modifier = Modifier.width(80.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = AppColors.DataGray,
                            focusedBorderColor = AppColors.Orange,
                            unfocusedTextColor = AppColors.DataGray,
                            focusedTextColor = AppColors.Orange
                        )
                    )
                    Text(
                        text = "minutes",
                        color = AppColors.DataGray
                    )
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
    onEnabledChange: (Boolean) -> Unit,
    onFrequencyChange: (Int) -> Unit,
    onThresholdChange: (Float) -> Unit
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
                    onCheckedChange = onEnabledChange
                )
            }
            Text(
                text = "Get notified when mempool size falls below threshold.",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.DataGray
            )
            if (enabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Check every",
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.DataGray
                    )
                    OutlinedTextField(
                        value = frequency.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { value ->
                                if (value > 0) onFrequencyChange(value)
                            }
                        },
                        modifier = Modifier.width(80.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = AppColors.DataGray,
                            focusedBorderColor = AppColors.Orange,
                            unfocusedTextColor = AppColors.DataGray,
                            focusedTextColor = AppColors.Orange
                        )
                    )
                    Text(
                        text = "minutes",
                        color = AppColors.DataGray
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Threshold",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.DataGray
                    )
                    OutlinedTextField(
                        value = threshold.toString(),
                        onValueChange = {
                            it.toFloatOrNull()?.let { value ->
                                if (value > 0) onThresholdChange(value)
                            }
                        },
                        modifier = Modifier.width(80.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = AppColors.DataGray,
                            focusedBorderColor = AppColors.Orange,
                            unfocusedTextColor = AppColors.DataGray,
                            focusedTextColor = AppColors.Orange
                        )
                    )
                    Text(
                        text = "vMB",
                        color = AppColors.DataGray
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
    onEnabledChange: (Boolean) -> Unit,
    onFrequencyChange: (Int) -> Unit,
    onFeeRateTypeChange: (FeeRateType) -> Unit,
    onThresholdChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

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
                    onCheckedChange = onEnabledChange
                )
            }
            Text(
                text = "Get notified when fee rates fall below threshold.",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.DataGray
            )
            if (enabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Check every",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.DataGray
                    )
                    OutlinedTextField(
                        value = frequency.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { value ->
                                if (value > 0) onFrequencyChange(value)
                            }
                        },
                        modifier = Modifier.width(80.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = AppColors.DataGray,
                            focusedBorderColor = AppColors.Orange,
                            unfocusedTextColor = AppColors.DataGray,
                            focusedTextColor = AppColors.Orange
                        )
                    )
                    Text(
                        text = "minutes",
                        color = AppColors.DataGray
                    )
                }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = when (selectedFeeRateType) {
                            FeeRateType.NEXT_BLOCK -> "Next Block"
                            FeeRateType.TWO_BLOCKS -> "2 Blocks"
                            FeeRateType.FOUR_BLOCKS -> "4 Blocks"
                        },
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor(),
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
                            text = { Text("2 Blocks") },
                            onClick = {
                                onFeeRateTypeChange(FeeRateType.TWO_BLOCKS)
                                expanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("4 Blocks") },
                            onClick = {
                                onFeeRateTypeChange(FeeRateType.FOUR_BLOCKS)
                                expanded = false
                            }
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Threshold",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.DataGray
                    )
                    OutlinedTextField(
                        value = threshold.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { value ->
                                if (value > 0) onThresholdChange(value)
                            }
                        },
                        modifier = Modifier.width(80.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = AppColors.DataGray,
                            focusedBorderColor = AppColors.Orange,
                            unfocusedTextColor = AppColors.DataGray,
                            focusedTextColor = AppColors.Orange
                        )
                    )
                    Text(
                        text = "sat/vB",
                        color = AppColors.DataGray
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Settings Coming Soon",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}