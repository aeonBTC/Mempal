package com.example.mempal

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.example.mempal.api.FeeRates
import com.example.mempal.api.MempoolInfo
import com.example.mempal.api.NetworkClient
import com.example.mempal.cache.DashboardCache
import com.example.mempal.model.FeeRateType
import com.example.mempal.model.NotificationSettings
import com.example.mempal.repository.SettingsRepository
import com.example.mempal.service.NotificationService
import com.example.mempal.tor.TorManager
import com.example.mempal.tor.TorStatus
import com.example.mempal.ui.theme.AppColors
import com.example.mempal.ui.theme.MempalTheme
import com.example.mempal.viewmodel.DashboardUiState
import com.example.mempal.viewmodel.MainViewModel
import com.example.mempal.widget.WidgetUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ceil
import androidx.compose.foundation.interaction.MutableInteractionSource


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

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition {
            false // Don't hold the splash screen, let the app load immediately
        }

        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Initialize NetworkClient and SettingsRepository
        NetworkClient.initialize(applicationContext)
        settingsRepository = SettingsRepository.getInstance(applicationContext)

        // Clear the server restart flag on app start
        settingsRepository.clearServerRestartFlag()

        // Add Tor connection event listener
        lifecycleScope.launch {
            TorManager.getInstance().torConnectionEvent.collect { connected ->
                if (connected && !viewModel.hasInitialData) {
                    // Only refresh if we don't have data yet
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

        // In onCreate, after the existing NetworkClient initialization
        lifecycleScope.launch {
            NetworkClient.isNetworkAvailable.collect { isAvailable ->
                if (isAvailable && NetworkClient.isInitialized.value) {
                    // Network is available and client is initialized, refresh data
                    viewModel.refreshData()
                }
            }
        }

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

        // Check and restore Tor connection if needed
        val torManager = TorManager.getInstance()
        torManager.checkAndRestoreTorConnection(applicationContext)

        // Reinitialize network client if needed
        if (!NetworkClient.isInitialized.value) {
            NetworkClient.initialize(applicationContext)
        } else {
            // Force a refresh of the dashboard data
            lifecycleScope.launch {
                // Wait a bit for any network/Tor connections to stabilize
                delay(1000)
                viewModel.refreshData()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources
        if (isFinishing) {
            // Only cleanup if activity is actually being destroyed, not recreated
            NetworkClient.cleanup()
            SettingsRepository.cleanup()
            TorManager.getInstance().cleanup()  // Add this line
            requestPermissionLauncher.unregister()
        }
    }

    private fun updateServiceState() {
        lifecycleScope.launch {
            // Use the new method from NotificationService
            NotificationService.syncServiceState(applicationContext)
        }
    }
}

@Composable
private fun MainScreen(viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val uiState by viewModel.uiState.collectAsState()
    val isInitialized by NetworkClient.isInitialized.collectAsState()

    // Effect to handle tab changes and periodic refresh
    LaunchedEffect(selectedTab) {
        // Notify ViewModel of tab change
        viewModel.onTabSelected(selectedTab)

        // Only set up periodic refresh for dashboard tab
        if (selectedTab == 0) {
            while (true) {
                delay(300000) // 5 minute delay between refreshes
                if (isInitialized) {
                    viewModel.refreshData()
                }
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
                            icon = { Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = null) },
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
                0 -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    AppHeader()
                    MainContent(
                        viewModel = viewModel,
                        uiState = uiState,
                        modifier = Modifier.padding(paddingValues)
                    )
                }
                1 -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    AppHeader()
                    NotificationsScreen(modifier = Modifier.padding(paddingValues))
                }
                2 -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    AppHeader()
                    SettingsScreen(modifier = Modifier.padding(paddingValues))
                }
            }
        }
    }
}

@Composable
private fun AppHeader() {
    val torManager = remember { TorManager.getInstance() }
    val torStatus by torManager.torStatus.collectAsState()
    val torEnabled = remember(torStatus) { torManager.isTorEnabled() }
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository.getInstance(context) }

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
                .clickable {
                    try {
                        val currentUrl = settingsRepository.getApiUrl().trim()
                        
                        // Check if it's an onion address
                        if (currentUrl.contains(".onion")) {
                            // Try Tor Browser first
                            val torBrowserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                setPackage("org.torproject.torbrowser")
                            }
                            
                            // Try Orbot Browser second
                            val orbotBrowserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                setPackage("org.torproject.android")
                            }
                            
                            try {
                                if (torBrowserIntent.resolveActivity(context.packageManager) != null) {
                                    context.startActivity(torBrowserIntent)
                                } else if (orbotBrowserIntent.resolveActivity(context.packageManager) != null) {
                                    context.startActivity(orbotBrowserIntent)
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Please install Tor Browser to open .onion links.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } catch (_: Exception) {
                                Toast.makeText(
                                    context,
                                    "Unable to open .onion link. Please install Tor Browser.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            // Regular URL handling
                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            
                            if (browserIntent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(browserIntent)
                            } else {
                                Toast.makeText(
                                    context,
                                    "No web browser found to open $currentUrl",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (e: Exception) {
                        val errorMessage = "Unable to open URL: ${e.localizedMessage}"
                        Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
                    }
                }
        )

        // Tor Status Indicator (moved to bottom end)
        if (torEnabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 27.dp, bottom = 18.dp)
            ) {
                var showTooltip by remember { mutableStateOf(false) }

                IconButton(
                    onClick = { showTooltip = !showTooltip },
                    modifier = Modifier.size(24.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_onion),
                        contentDescription = "Tor Status",
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer(alpha = when (torStatus) {
                                TorStatus.CONNECTED -> 1f
                                else -> 0.5f
                            })
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
                                text = when (torStatus) {
                                    TorStatus.CONNECTED -> "Tor Connected"
                                    TorStatus.CONNECTING -> "Tor Connecting..."
                                    else -> "Tor Disconnected"
                                },
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainContent(
    viewModel: MainViewModel,
    uiState: DashboardUiState,
    modifier: Modifier = Modifier
) {
    val blockHeight by viewModel.blockHeight.observeAsState()
    val blockTimestamp by viewModel.blockTimestamp.observeAsState()
    val feeRates by viewModel.feeRates.observeAsState()
    val mempoolInfo by viewModel.mempoolInfo.observeAsState()
    val isInitialized = NetworkClient.isInitialized.collectAsState()
    val torManager = remember { TorManager.getInstance() }
    val torStatus by torManager.torStatus.collectAsState()
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val isUsingOnion = remember(settingsRepository) { settingsRepository.getApiUrl().contains(".onion") }

    // Effect to refresh data when Tor connects and we're using an onion address
    LaunchedEffect(torStatus, isUsingOnion) {
        if (isUsingOnion && torStatus == TorStatus.CONNECTED && 
            (blockHeight == null || feeRates == null || mempoolInfo == null)) {
            delay(100) // Small delay to ensure Tor circuit is ready
            viewModel.refreshData()
        }
    }

    // Effect to periodically check and refresh if data is missing
    LaunchedEffect(Unit) {
        while (true) {
            if (isUsingOnion && torStatus == TorStatus.CONNECTED && 
                (blockHeight == null || feeRates == null || mempoolInfo == null)) {
                viewModel.refreshData()
            }
            delay(3000) // Check every 3 seconds
        }
    }

    // Determine the appropriate message based on Tor connection and cache state
    val statusMessage = when {
        // If using onion and Tor is not connected
        isUsingOnion && torStatus != TorStatus.CONNECTED -> {
            if (DashboardCache.hasCachedData()) "Waiting for Tor connection..." 
            else "Connecting to Tor network..."
        }
        
        // If we're loading data
        isInitialized.value && uiState is DashboardUiState.Loading -> "Fetching data..."
        
        // If Tor is enabled and not connected or connecting
        torManager.isTorEnabled() && (!isInitialized.value || torStatus == TorStatus.CONNECTING) -> {
            if (DashboardCache.hasCachedData()) "Reconnecting to Tor network..."
            else "Connecting to Tor network..."
        }
        
        // If not using Tor but network is not initialized
        !torManager.isTorEnabled() && !isInitialized.value -> {
            if (DashboardCache.hasCachedData()) "Reconnecting to server..."
            else "Connecting to server..."
        }
        
        // For error states
        uiState is DashboardUiState.Error -> {
            if (torManager.isTorEnabled() && 
                (uiState.message.contains("Connecting to Tor") || uiState.message.contains("Reconnecting to Tor"))) {
                if (DashboardCache.hasCachedData()) "Reconnecting to Tor network..." 
                else "Connecting to Tor network..."
            } else {
                uiState.message
            }
        }
        
        // For success states with cache
        uiState is DashboardUiState.Success && uiState.isCache -> {
            if (torManager.isTorEnabled()) {
                if (DashboardCache.hasCachedData()) "Reconnecting to Tor network..." 
                else "Connecting to Tor network..."
            } else {
                if (DashboardCache.hasCachedData()) "Reconnecting to server..." 
                else "Connecting to server..."
            }
        }
        
        // No message for other states
        else -> null
    }

    when (uiState) {
        is DashboardUiState.Error -> {
            MainContentDisplay(
                blockHeight = blockHeight,
                blockTimestamp = blockTimestamp,
                feeRates = feeRates,
                mempoolInfo = mempoolInfo,
                modifier = modifier,
                viewModel = viewModel,
                statusMessage = statusMessage
            )
        }
        is DashboardUiState.Success -> {
            MainContentDisplay(
                blockHeight = blockHeight,
                blockTimestamp = blockTimestamp,
                feeRates = feeRates,
                mempoolInfo = mempoolInfo,
                modifier = modifier,
                viewModel = viewModel,
                statusMessage = statusMessage
            )
        }
        DashboardUiState.Loading -> {
            MainContentDisplay(
                blockHeight = blockHeight,
                blockTimestamp = blockTimestamp,
                feeRates = feeRates,
                mempoolInfo = mempoolInfo,
                modifier = modifier,
                viewModel = viewModel,
                statusMessage = statusMessage
            )
        }
    }
}

@Composable
private fun MainContentDisplay(
    blockHeight: Int?,
    blockTimestamp: Long?,
    feeRates: FeeRates?,
    mempoolInfo: MempoolInfo?,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel? = null,
    statusMessage: String? = null
) {
    val isInitialized = NetworkClient.isInitialized.collectAsState()
    val isMainRefreshing by viewModel?.isMainRefreshing?.collectAsState() ?: remember { mutableStateOf(false) }
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    var visibleCards by remember { mutableStateOf(settingsRepository.getVisibleCards()) }
    var isRefreshing by remember { mutableStateOf(false) }
    var lastRefreshTime by remember { mutableLongStateOf(0L) }

    // Reset refresh state after a delay
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            delay(10) // Wait for 10ms
            isRefreshing = false
        }
    }

    // Common refresh function for all cards
    val refreshAll = {
        if (!isMainRefreshing && isInitialized.value) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastRefreshTime >= 5000) {
                isRefreshing = true
                lastRefreshTime = currentTime
                viewModel?.refreshData()
            }
        }
    }

    // Update visible cards when they change in settings
    LaunchedEffect(Unit) {
        visibleCards = settingsRepository.getVisibleCards()
    }
    
    // Remember the warning tooltip state
    val warningTooltip = remember(mempoolInfo) {
        if (mempoolInfo?.isUsingFallbackHistogram == true) {
            "Your custom server doesn't provide fee distribution data. " +
            "We're using mempool.space as a fallback source for this information."
        } else null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Status message at the top
        if (!statusMessage.isNullOrEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = AppColors.DarkerNavy
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 8.dp),
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

        // Conditionally render cards based on user preferences
        if ("Block Height" in visibleCards) {
            DataCard(
                title = "Block Height",
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
                                    text = "(${elapsedMinutes} ${if (elapsedMinutes == 1L) "minute" else "minutes"} ago)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.DataGray.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                },
                isLoading = blockHeight == null,
                onRefresh = refreshAll,
                isRefreshing = isRefreshing || isMainRefreshing
            )
        }

        if ("Mempool Size" in visibleCards) {
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
                                text = "(${blocksToClean} ${if (blocksToClean == 1) "block" else "blocks"} to clear)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.DataGray.copy(alpha = 0.7f)
                            )
                        }
                    }
                },
                isLoading = mempoolInfo == null,
                onRefresh = refreshAll,
                isRefreshing = isRefreshing || isMainRefreshing
            )
        }

        if ("Fee Rates" in visibleCards) {
            DataCard(
                title = "Fee Rates",
                content = feeRates?.let { { FeeRatesContent(it) } },
                icon = Icons.Default.Timeline,
                tooltip = "This section shows the average recommended fee rate with estimated confirmation times." +
                        "\n\nNOTE: The mempool can sometimes experience a flood of transactions, leading to drastically higher fees. " +
                        "These floods are often only a few vMB and clear quickly. To avoid overpaying fees, use the " +
                        "\"Fee Distribution\" table to gauge the size and clearing time of the flood.",
                isLoading = feeRates == null,
                onRefresh = refreshAll,
                isRefreshing = isRefreshing || isMainRefreshing
            )
        }

        if ("Fee Distribution" in visibleCards) {
            DataCard(
                title = "Fee Distribution",
                content = mempoolInfo?.let { { HistogramContent(it) } },
                icon = Icons.Default.BarChart,
                tooltip = "This section shows a detailed breakdown of the mempool. Fee ranges are shown on the left and " +
                        "the cumulative size of transactions on the right" +
                        "\n\nRange Key:" +
                        "\n- Green will confirm in the next block." +
                        "\n- Yellow might confirm in the next block." +
                        "\n- Red will not confirm in the next block." +
                        "\n\nNOTE: Each Bitcoin block confirms about 1.5 vMB worth of transactions.",
                warningTooltip = warningTooltip,
                isLoading = mempoolInfo == null,
                onRefresh = refreshAll,
                isRefreshing = isRefreshing || isMainRefreshing
            )
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
    warningTooltip: String? = null,
    isLoading: Boolean = value == null && content == null,
    onRefresh: (() -> Unit)? = null,
    isRefreshing: Boolean = false
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                indication = null, // Remove ripple animation
                interactionSource = remember { MutableInteractionSource() } // Required to remove ripple
            ) {
                if (!isLoading && !isRefreshing) {
                    onRefresh?.invoke()
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = AppColors.DarkerNavy
        )
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
                if (tooltip != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    TooltipButton(tooltip = tooltip)
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (value != null) "$value" else "...",
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
private fun HistogramContent(mempoolInfo: MempoolInfo) {
    // Remember the last valid histogram data
    var lastValidHistogram by remember { mutableStateOf<List<List<Double>>>(emptyList()) }
    
    // Update last valid histogram when we get new data
    LaunchedEffect(mempoolInfo.feeHistogram) {
        if (mempoolInfo.feeHistogram.isNotEmpty()) {
            lastValidHistogram = mempoolInfo.feeHistogram
        }
    }

    // Use either current or last valid histogram data
    val histogramToDisplay = if (mempoolInfo.feeHistogram.isNotEmpty()) {
        mempoolInfo.feeHistogram
    } else {
        lastValidHistogram
    }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        if (histogramToDisplay.isNotEmpty()) {
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

            // Process and show histogram data
            val sizeMap = mutableMapOf<String, Double>()
            var totalSize = 0.0

            // Process the raw data first
            histogramToDisplay.forEach { feeRange ->
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

            // Calculate running sum and show entries
            var runningSum = 0.0
            nonZeroEntries.forEach { entry ->
                runningSum += entry.value
                val sumInMB = runningSum / 1_000_000
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = entry.key,
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
        } else {
            // Loading state - align with header text
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
            .padding(horizontal = 12.dp)
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)  // Changed from 12.dp to 6.dp
    ) {
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

        // Add this effect to monitor notification states
        LaunchedEffect(
            settings.blockNotificationsEnabled,
            settings.newBlockNotificationEnabled,
            settings.specificBlockNotificationEnabled,
            settings.mempoolSizeNotificationsEnabled,
            settings.feeRatesNotificationsEnabled,
            settings.txConfirmationEnabled,
            settings.transactionId
        ) {
            // Check if any notifications are enabled
            val isAnyEnabled = settings.run {
                blockNotificationsEnabled && (newBlockNotificationEnabled || specificBlockNotificationEnabled) ||
                mempoolSizeNotificationsEnabled ||
                feeRatesNotificationsEnabled ||
                (txConfirmationEnabled && transactionId.isNotEmpty())
            }

            // If no notifications are enabled but service is running, stop it
            if (!isAnyEnabled && settings.isServiceEnabled) {
                val serviceIntent = Intent(context, NotificationService::class.java)
                context.stopService(serviceIntent)
                settingsRepository.updateSettings(settings.copy(isServiceEnabled = false))
            }
        }

        // Bitcoin Blocks section
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = AppColors.DarkerNavy
        )
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
                            label = "Check Interval (minutes)"
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
                                label = "Check Interval (minutes)"
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
    var debouncedThreshold by remember(threshold) { mutableStateOf(if (threshold == 0f) "" else threshold.toString()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = AppColors.DarkerNavy
        )
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
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThresholdToggle(
                        isAboveThreshold = aboveThreshold,
                        onToggleChange = onAboveThresholdChange
                    )

                    OutlinedTextField(
                        value = debouncedThreshold,
                        onValueChange = { newValue ->
                            // Allow empty, digits, and a single decimal point
                            if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                                debouncedThreshold = newValue
                                if (newValue.isEmpty()) {
                                    onThresholdChange(0f)
                                } else {
                                    newValue.toFloatOrNull()?.let { value ->
                                        onThresholdChange(value)
                                    }
                                }
                            }
                        },
                        label = { Text("Threshold (vMB)", color = AppColors.DataGray) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
                        label = "Check Interval (minutes)"
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
    var debouncedThreshold by remember(threshold) { mutableIntStateOf(threshold) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = AppColors.DarkerNavy
        )
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
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThresholdToggle(
                        isAboveThreshold = isAboveThreshold,
                        onToggleChange = onAboveThresholdChange
                    )

                    NumericTextField(
                        value = if (debouncedThreshold == 0) "" else debouncedThreshold.toString(),
                        onValueChange = { newValue ->
                            if (newValue.isEmpty()) {
                                debouncedThreshold = 0
                                onThresholdChange(0)
                            } else {
                                newValue.toIntOrNull()?.let { value ->
                                    debouncedThreshold = value
                                    onThresholdChange(value)
                                }
                            }
                        },
                        label = "Threshold (sat/vB)"
                    )

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
                        label = "Check Interval (minutes)"
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
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                        label = "Check Interval (minutes)"
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
    
    // Track initial values
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

    // New state for visible cards
    var visibleCards by remember { mutableStateOf(settingsRepository.getVisibleCards()) }

    // Check if settings have changed
    val hasServerSettingsChanged = selectedOption != initialSelectedOption ||
            (selectedOption == 1 && customUrl != initialApiUrl) ||
            torEnabled != initialTorEnabled ||
            visibleCards != settingsRepository.getVisibleCards()

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
            customUrl.trim().trimEnd('/')
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
        // Save Tor state when user clicks Save
        torManager.saveTorState(torEnabled)
        // Save visible cards
        settingsRepository.saveVisibleCards(visibleCards)
        showRestartDialog = true
    }

    // Add this function to handle server testing
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
            .padding(horizontal = 12.dp)
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)  // Changed from 12.dp to 6.dp
    ) {
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
                        
                        // Function to check if URL is mempool.space
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
                                // Only allow auto-collapse, not auto-expand
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
                                    }
                                },
                                label = { Text(if (torEnabled) "Onion Address" else "Server Address", color = AppColors.DataGray) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AppColors.Orange,
                                    unfocusedBorderColor = AppColors.DataGray,
                                    unfocusedTextColor = AppColors.DataGray,
                                    focusedTextColor = AppColors.Orange
                                ),
                                isError = showUrlError,
                                supportingText = if (showUrlError) {
                                    { Text(
                                        text = if (isDefaultServer(customUrl)) 
                                            "Use default server option instead" 
                                        else "URL must start with http:// or https://",
                                        color = MaterialTheme.colorScheme.error
                                    ) }
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
                                                        tint = Color(0xFFA00000)  // Brighter Dark Red
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            customUrl = serverUrl.trimEnd('/')
                                            if (serverUrl.contains(".onion")) {
                                                torEnabled = true
                                                torManager.startTor(context)
                                            }
                                            isDropdownExpanded = false
                                        },
                                        contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                        colors = MenuDefaults.itemColors(
                                            textColor = AppColors.DataGray
                                        )
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
                                        colors = MenuDefaults.itemColors(
                                            textColor = AppColors.DataGray
                                        )
                                    )
                                }
                            }
                        }
                        // Connection status indicator
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

                        LaunchedEffect(customUrl, selectedOption, torEnabled, torStatus) {
                            if (selectedOption == 1 && customUrl.isNotEmpty() && 
                                (customUrl.startsWith("http://") || customUrl.startsWith("https://"))) {
                                isTestingConnection = true
                                // Wait for changes before testing - longer delay for .onion addresses
                                delay(if (customUrl.contains(".onion")) 2000L else 500L)
                                // If Tor is enabled, wait until it's connected
                                if (torEnabled && torStatus != TorStatus.CONNECTED) {
                                    testResult = null
                                    isTestingConnection = false
                                    return@LaunchedEffect
                                }
                                // First attempt
                                testResult = testServerConnection(customUrl)
                                // If first attempt fails, wait and try again - longer delay for .onion
                                if (testResult == false) {
                                    delay(if (customUrl.contains(".onion")) 2000L else 500L)
                                    testResult = testServerConnection(customUrl)
                                }
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
                                if (customUrl.isEmpty() || !customUrl.contains(".onion")) {
                                    customUrl = "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion"
                                }
                            } else {
                                torManager.stopTor(context)
                            }
                        },
                        enabled = torStatus != TorStatus.CONNECTING,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AppColors.DarkerNavy,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = AppColors.DarkerNavy,
                            checkedBorderColor = Color.Gray,
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
                            !(torEnabled && torStatus != TorStatus.CONNECTED), // Disable if Tor is enabled but not connected
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

        // Notifications Settings Card
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

                // Force minutes when using default server or before restart
                val selectedTimeUnit = remember {
                    if (!timeUnitEnabled) {
                        settingsRepository.saveNotificationTimeUnit("minutes")
                        mutableStateOf("minutes")
                    } else {
                        mutableStateOf(settingsRepository.getNotificationTimeUnit())
                    }
                }

                // Update time unit if server changes or restart status changes
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
                            .menuAnchor()
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
                        tooltip = "Any widget can be manually updated by tapping it once. You can also double tap any widget to open the Mempal app."
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
                        label = { Text("Update Interval") },
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
                        listOf(5L, 15L, 30L, 45L).forEach { minutes ->
                            DropdownMenuItem(
                                text = {
                                    Text("$minutes minutes")
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
                    text = "Widgets will auto-update every $updateFrequency minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.DataGray,
                    modifier = Modifier.padding(top = 4.dp, start = 12.dp)
                )
            }
        }

        // Dashboard Cards section - Moved to bottom
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
                    text = "Dashboard",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppColors.Orange
                )

                Spacer(modifier = Modifier.height(8.dp))

                // List of available cards
                val availableCards = listOf("Block Height", "Mempool Size", "Fee Rates", "Fee Distribution")

                availableCards.forEach { cardName ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                visibleCards = if (cardName in visibleCards) {
                                    visibleCards - cardName
                                } else {
                                    visibleCards + cardName
                                }
                                // Save immediately when toggling cards
                                settingsRepository.saveVisibleCards(visibleCards)
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = AppColors.DataGray.copy(alpha = 0.05f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = cardName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (cardName in visibleCards) 
                                    AppColors.DataGray
                                else 
                                    AppColors.DataGray.copy(alpha = 0.5f)
                            )
                            Checkbox(
                                checked = cardName in visibleCards,
                                onCheckedChange = null, // Handled by card click
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
                        data = Uri.parse("https://github.com/aeonBTC/Mempal/")
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
                Text("Please restart the app to save your custom server settings.")
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
private fun NumericTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    var fieldValue by remember(value) { mutableStateOf(value) }
    val timeUnit = settingsRepository.getNotificationTimeUnit()
    val isCheckIntervalField = label.startsWith("Check Interval")

    // Only apply time unit conversion for check interval fields
    val displayValue = if (isCheckIntervalField) {
        if (fieldValue.isNotEmpty()) {
            fieldValue
        } else {
            ""
        }
    } else {
        fieldValue
    }

    OutlinedTextField(
        value = displayValue,
        onValueChange = { newValue ->
            if (newValue.isEmpty() || newValue.all { char -> char.isDigit() }) {
                val numericValue = newValue.toIntOrNull() ?: 0
                if (newValue.isEmpty() || numericValue >= NotificationSettings.MIN_CHECK_FREQUENCY) {
                    fieldValue = newValue
                    onValueChange(newValue)
                }
            }
        },
        label = {
            Text(
                if (isCheckIntervalField) {
                    "Check Interval (${if (timeUnit == "seconds") "seconds" else "minutes"})"
                } else {
                    label
                },
                color = AppColors.DataGray
            )
        },
        modifier = modifier.fillMaxWidth(),
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