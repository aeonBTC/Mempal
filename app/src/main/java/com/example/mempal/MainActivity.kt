package com.example.mempal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.example.mempal.api.NetworkClient
import com.example.mempal.repository.SettingsRepository
import com.example.mempal.service.NotificationService
import com.example.mempal.tor.TorManager
import com.example.mempal.tor.TorStatus
import com.example.mempal.ui.screens.AddressType
import com.example.mempal.ui.screens.DashboardContent
import com.example.mempal.ui.screens.FeeEstimatorScreen
import com.example.mempal.ui.screens.NotificationsScreen
import com.example.mempal.ui.screens.SettingsScreen
import com.example.mempal.ui.screens.WelcomeDialog
import com.example.mempal.ui.theme.AppColors
import com.example.mempal.ui.theme.MempalTheme
import com.example.mempal.viewmodel.DashboardUiState
import com.example.mempal.viewmodel.MainViewModel
import com.example.mempal.widget.WidgetUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var settingsRepository: SettingsRepository

    val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            val settings = settingsRepository.settings.value
            if (settings.isServiceEnabled) {
                // Validate settings before starting service
                val validationError = validateNotificationSettings(settings)
                if (validationError != null) {
                    // Disable service if validation fails
                    settingsRepository.updateSettings(settings.copy(isServiceEnabled = false))
                    Toast.makeText(this, validationError, Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                
                // Start or restart the service to ensure notification is shown
                val serviceIntent = Intent(this, NotificationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
        } else {
            // If permission was denied, disable the service since it can't run without permission
            val currentSettings = settingsRepository.settings.value
            if (currentSettings.isServiceEnabled) {
                settingsRepository.updateSettings(currentSettings.copy(isServiceEnabled = false))
            }
            Toast.makeText(this, "Notifications disabled", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun validateNotificationSettings(settings: com.example.mempal.model.NotificationSettings): String? {
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

    private fun handleNotificationPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startNotificationServiceIfEnabled()
            }
        } else {
            startNotificationServiceIfEnabled()
        }
    }

    private fun startNotificationServiceIfEnabled() {
        val settings = settingsRepository.settings.value
        if (settings.isServiceEnabled) {
            val serviceIntent = Intent(this, NotificationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition {
            !NetworkClient.isInitialized.value
        }

        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        NetworkClient.initialize(applicationContext)
        settingsRepository = SettingsRepository.getInstance(applicationContext)
        
        viewModel.setContext(applicationContext)

        settingsRepository.clearServerRestartFlag()

        handleNotificationPermissions()

        lifecycleScope.launch {
            // Tor connection events - refresh data when Tor connects
            launch {
                TorManager.getInstance().torConnectionEvent.collect { connected ->
                    if (connected && !viewModel.hasInitialData) {
                        val usePreciseFees = settingsRepository.settings.value.usePreciseFees
                        viewModel.setUsePreciseFees(usePreciseFees)
                        viewModel.refreshData(usePreciseFees, isManualRefresh = false)
                    }
                }
            }

            // Settings changes - manage notification service
            launch {
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

            // Network state changes - combine isInitialized and isNetworkAvailable to avoid nested collectors
            launch {
                kotlinx.coroutines.flow.combine(
                    NetworkClient.isInitialized,
                    NetworkClient.isNetworkAvailable
                ) { isInitialized, isAvailable -> 
                    isInitialized && isAvailable 
                }.collect { canRefresh ->
                    if (canRefresh && viewModel.shouldAutoRefresh()) {
                        val usePreciseFees = settingsRepository.settings.value.usePreciseFees
                        viewModel.setUsePreciseFees(usePreciseFees)
                        viewModel.refreshData(usePreciseFees, isManualRefresh = false)
                        WidgetUpdater.requestImmediateUpdate(applicationContext)
                    }
                }
            }

            // UI state changes - update widgets on successful data fetch
            launch {
                viewModel.uiState.collect { uiState ->
                    if (uiState is DashboardUiState.Success && !uiState.isCache) {
                        WidgetUpdater.requestImmediateUpdate(applicationContext)
                    }
                }
            }
        }

        updateServiceState()

        setContent {
            MempalTheme {
                MainScreen(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceState()

        val torManager = TorManager.getInstance()
        torManager.checkAndRestoreTorConnection(applicationContext)

        if (!NetworkClient.isInitialized.value) {
            NetworkClient.initialize(applicationContext)
        } else {
            // Update widgets without delay
            try {
                WidgetUpdater.requestImmediateUpdate(applicationContext)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // Delay and then check if refresh is needed (check AFTER delay to avoid race with init refresh)
            lifecycleScope.launch {
                delay(500)
                // Check conditions after delay - ViewModel init may have already refreshed
                if (!viewModel.hasInitialData || viewModel.shouldAutoRefresh()) {
                    val usePreciseFees = settingsRepository.settings.value.usePreciseFees
                    viewModel.setUsePreciseFees(usePreciseFees)
                    viewModel.refreshData(usePreciseFees, isManualRefresh = false)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            NetworkClient.cleanup()
            SettingsRepository.cleanup()
            TorManager.getInstance().cleanup()
            requestPermissionLauncher.unregister()
        }
    }

    private fun updateServiceState() {
        lifecycleScope.launch {
            NotificationService.syncServiceState(applicationContext)
        }
    }
}

@Composable
private fun MainScreen(viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val uiState by viewModel.uiState.collectAsState()
    val isInitialized by NetworkClient.isInitialized.collectAsState()
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    var showWelcomeDialog by remember { mutableStateOf(settingsRepository.isFirstLaunch()) }
    val activity = context as MainActivity
    val isRefreshing by viewModel.isMainRefreshing.collectAsState()
    val scope = rememberCoroutineScope()
    val currentSettings by settingsRepository.settings.collectAsState()
    
    // Fee Estimator state (persists across tab switches)
    var feeEstNumInputs by remember { mutableStateOf("1") }
    var feeEstNumOutputs by remember { mutableStateOf("2") }
    var feeEstAddressType by remember { mutableStateOf(AddressType.SEGWIT) }
    var feeEstFeeRateOption by remember { mutableStateOf("") }
    var feeEstCustomFeeRate by remember { mutableStateOf("") }
    var feeEstIsMultisig by remember { mutableStateOf(false) }
    var feeEstRequiredSigs by remember { mutableStateOf("2") }
    var feeEstTotalKeys by remember { mutableStateOf("3") }

    LaunchedEffect(currentSettings.usePreciseFees) {
        viewModel.setUsePreciseFees(currentSettings.usePreciseFees)
    }

    LaunchedEffect(selectedTab) {
        viewModel.onTabSelected(selectedTab)
    }

    // Auto-refresh every 5 minutes when on dashboard tab
    LaunchedEffect(selectedTab, isInitialized) {
        if (selectedTab == 0 && isInitialized) {
            while (true) {
                delay(300000) // 5 minutes
                if (selectedTab == 0 && viewModel.shouldAutoRefresh()) {
                    val usePreciseFees = settingsRepository.settings.value.usePreciseFees
                    viewModel.setUsePreciseFees(usePreciseFees)
                    viewModel.refreshData(usePreciseFees, isManualRefresh = false)
                }
            }
        }
    }

    if (showWelcomeDialog) {
        WelcomeDialog(
            onDismiss = {
                settingsRepository.setFirstLaunchComplete()
                showWelcomeDialog = false
            }
        )
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
                            icon = { Icon(Icons.Default.Calculate, contentDescription = null) },
                            label = {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Fee Estimator",
                                        textAlign = TextAlign.Center,
                                        maxLines = 2
                                    )
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppColors.Orange,
                                selectedTextColor = AppColors.Orange,
                                indicatorColor = AppColors.DarkerNavy,
                                unselectedIconColor = AppColors.DataGray,
                                unselectedTextColor = AppColors.DataGray
                            )
                        )
                        NavigationBarItem(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
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
                0 -> {
                    val pullToRefreshState = rememberPullToRefreshState()
                    val blockHeight by viewModel.blockHeight.observeAsState()
                    val feeRates by viewModel.feeRates.observeAsState()
                    val hasData = blockHeight != null || feeRates != null
                    val showInitialText = isRefreshing && !hasData
                    
                    Box(modifier = Modifier.fillMaxSize()) {
                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = { 
                                scope.launch {
                                    val usePreciseFees = settingsRepository.settings.value.usePreciseFees
                                    viewModel.setUsePreciseFees(usePreciseFees)
                                    viewModel.refreshData(usePreciseFees, isManualRefresh = true)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            state = pullToRefreshState,
                            indicator = {
                                Box(modifier = Modifier.size(0.dp))
                            }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                AppHeader()
                                DashboardContent(
                                    viewModel = viewModel,
                                    uiState = uiState,
                                    modifier = Modifier.padding(paddingValues),
                                    pullToRefreshState = pullToRefreshState
                                )
                            }
                        }
                        
                        if (isRefreshing) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (showInitialText) {
                                            Modifier.background(Color.Black.copy(alpha = 0.3f))
                                        } else {
                                            Modifier
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (showInitialText) {
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = AppColors.DarkerNavy
                                        ),
                                        modifier = Modifier.padding(24.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(24.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(48.dp),
                                                color = AppColors.Orange,
                                                strokeWidth = 4.dp
                                            )
                                            Text(
                                                text = "Fetching data...",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = AppColors.DataGray
                                            )
                                        }
                                    }
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(48.dp),
                                        color = AppColors.Orange,
                                        strokeWidth = 4.dp
                                    )
                                }
                            }
                        }
                    }
                }
                1 -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    AppHeader()
                    NotificationsScreen(
                        modifier = Modifier.padding(paddingValues),
                        requestPermissionLauncher = activity.requestPermissionLauncher
                    )
                }
                2 -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    AppHeader()
                    FeeEstimatorScreen(
                        modifier = Modifier.padding(paddingValues),
                        viewModel = viewModel,
                        numInputs = feeEstNumInputs,
                        onNumInputsChange = { feeEstNumInputs = it },
                        numOutputs = feeEstNumOutputs,
                        onNumOutputsChange = { feeEstNumOutputs = it },
                        selectedAddressType = feeEstAddressType,
                        onAddressTypeChange = { feeEstAddressType = it },
                        selectedFeeRateOption = feeEstFeeRateOption,
                        onFeeRateOptionChange = { feeEstFeeRateOption = it },
                        customFeeRate = feeEstCustomFeeRate,
                        onCustomFeeRateChange = { feeEstCustomFeeRate = it },
                        isMultisig = feeEstIsMultisig,
                        onMultisigChange = { feeEstIsMultisig = it },
                        requiredSigs = feeEstRequiredSigs,
                        onRequiredSigsChange = { feeEstRequiredSigs = it },
                        totalKeys = feeEstTotalKeys,
                        onTotalKeysChange = { feeEstTotalKeys = it }
                    )
                }
                3 -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    AppHeader()
                    SettingsScreen(
                        modifier = Modifier.padding(paddingValues),
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun AppHeader() {
    val torManager = TorManager.getInstance()
    val torStatus by torManager.torStatus.collectAsState()
    val torEnabled = torManager.isTorEnabled()
    val context = LocalContext.current
    val settingsRepository = SettingsRepository.getInstance(context)

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

                        if (currentUrl.contains(".onion")) {
                            val torBrowserIntent = Intent(Intent.ACTION_VIEW, currentUrl.toUri()).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                setPackage("org.torproject.torbrowser")
                            }

                            val orbotBrowserIntent = Intent(Intent.ACTION_VIEW, currentUrl.toUri()).apply {
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
                            val browserIntent = Intent(Intent.ACTION_VIEW, currentUrl.toUri())
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
