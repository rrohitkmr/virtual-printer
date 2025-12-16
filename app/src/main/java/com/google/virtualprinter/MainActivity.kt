/*
 * Copyright 2025 The Virtual Printer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.virtualprinter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.virtualprinter.printer.PrinterService
import com.google.virtualprinter.settings.SettingsScreen
import com.google.virtualprinter.ui.theme.PrinterTheme
import com.google.virtualprinter.utils.FileUtils
import com.google.virtualprinter.utils.DocumentConverter
import com.google.virtualprinter.utils.DocumentDiagnostics
import com.google.virtualprinter.utils.PreferenceUtils
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.google.virtualprinter.printer.PrinterForegroundService
import com.google.virtualprinter.ui.permission.NotificationPermissionHandler
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: PrinterViewModel by viewModels()
    enum class PermissionUiState {
        CHECKING,
        GRANTED,
        REQUIRED
    }
    private var boundService: PrinterForegroundService? = null
    private var isBound = false
    private val printJobReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            android.util.Log.d("MainActivity", "Received broadcast: ${intent.action}")
            if (intent.action == "com.google.virtualprinter.NEW_PRINT_JOB") {
                val jobPath = intent.getStringExtra("job_path") ?: "unknown"
                val jobSize = intent.getIntExtra("job_size", 0)
                android.util.Log.d("MainActivity", "Print job received: $jobPath, size: $jobSize bytes")
                
                // Trigger immediate refresh
                viewModel.triggerRefresh()
                android.util.Log.d("MainActivity", "Triggered UI refresh via ViewModel")
            }
        }
    }
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? PrinterForegroundService.LocalBinder
            if (binder != null) {
                boundService = binder.getService()
            } else {
                boundService = PrinterForegroundService.getInstance()
            }
            isBound = boundService != null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            isBound = false
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register the broadcast receiver with API level check
        val intentFilter = android.content.IntentFilter("com.google.virtualprinter.NEW_PRINT_JOB")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                printJobReceiver,
                intentFilter,
                android.content.Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(printJobReceiver, intentFilter)
        }
        
        // Try to fix existing data files by renaming them to PDF
        fixDataFiles()
        
        setContent {
            PrinterTheme {
                val context = this

                // Permission launcher for requesting permission
                var permissionState by remember {
                    mutableStateOf(PermissionUiState.CHECKING)
                }

                val permissionLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        permissionState = if (granted) {
                            startAndBindPrinterService(context)
                            PermissionUiState.GRANTED
                        } else {
                            PermissionUiState.REQUIRED
                        }
                    }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val alreadyGranted =
                            ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED

                        if (alreadyGranted) {
                            startAndBindPrinterService(context)
                            permissionState = PermissionUiState.GRANTED
                        } else {
                            permissionLauncher.launch(
                                android.Manifest.permission.POST_NOTIFICATIONS
                            )
                        }
                    } else {
                        permissionState = PermissionUiState.GRANTED
                        startAndBindPrinterService(context)
                    }
                }

                when (permissionState) {

                    PermissionUiState.CHECKING -> {
                    }

                    PermissionUiState.GRANTED -> {
                        MainNavigation(
                            printerService = PrinterService(this),
                            viewModel = viewModel
                        )
                    }

                    PermissionUiState.REQUIRED -> {
                        NotificationPermissionHandler(
                            onGrantClick = {
                                permissionLauncher.launch(
                                    android.Manifest.permission.POST_NOTIFICATIONS
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            try {
                unbindService(connection)
            } catch (e: Exception) {
                Log.w("MainActivity", "Unbind error", e)
            }
        }
        try {
            unregisterReceiver(printJobReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

    private fun startAndBindPrinterService(context: Context) {
        Intent(context, PrinterForegroundService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    /**
     * Attempts to fix existing .data files by renaming them to proper extensions
     */
    private fun fixDataFiles() {
        try {
            val printJobsDir = File(filesDir, "print_jobs")
            if (!printJobsDir.exists()) {
                printJobsDir.mkdirs()
                return
            }
            
            val dataFiles = printJobsDir.listFiles { file -> 
                file.name.endsWith(".data") 
            } ?: return
            
            android.util.Log.d("MainActivity", "Found ${dataFiles.size} .data files to fix")
            
            dataFiles.forEach { file ->
                try {
                    // Check if it's a PDF by reading the first few bytes
                    val isPdf = file.inputStream().use { stream ->
                        val bytes = ByteArray(4)
                        stream.read(bytes, 0, bytes.size)
                        bytes.size >= 4 && 
                        bytes[0] == '%'.toByte() && 
                        bytes[1] == 'P'.toByte() && 
                        bytes[2] == 'D'.toByte() && 
                        bytes[3] == 'F'.toByte()
                    }
                    
                    // Create a new name replacing .data with .pdf
                    val newName = if (isPdf) {
                        file.name.replace(".data", ".pdf")
                    } else {
                        // Default to PDF if we can't determine the type
                        file.name.replace(".data", ".pdf")
                    }
                    
                    val newFile = File(printJobsDir, newName)
                    val success = file.renameTo(newFile)
                    android.util.Log.d("MainActivity", "Renamed ${file.name} to ${newFile.name}: $success")
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Error renaming file ${file.name}", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error fixing data files", e)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigation(printerService: PrinterService,viewModel: PrinterViewModel) {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf("main") }
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Printer") },
                    selected = currentScreen == "main",
                    onClick = { currentScreen = "main" }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text("Jobs") },
                    selected = currentScreen == "jobs",
                    onClick = { currentScreen = "jobs" }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    label = { Text("Logs") },
                    selected = currentScreen == "logs",
                    onClick = { currentScreen = "logs" }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                    label = { Text("Plugins") },
                    selected = currentScreen == "plugins",
                    onClick = { currentScreen = "plugins" }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                    selected = currentScreen == "settings",
                    onClick = { currentScreen = "settings" }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (currentScreen) {
                "main" -> PrinterApp(printerService = printerService,viewModel = viewModel)
                "settings" -> SettingsScreen(
                    printerService = printerService,
                    onBackClick = { currentScreen = "main" }
                )
                "jobs" -> com.google.virtualprinter.ui.JobManagementScreen(
                    onBackClick = { currentScreen = "main" }
                )
                "logs" -> com.google.virtualprinter.ui.LogsScreen(
                    onBackClick = { currentScreen = "main" }
                )
                "plugins" -> com.google.virtualprinter.ui.PluginManagementScreen(
                    onBackClick = { currentScreen = "main" },
                    printerService = printerService
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterApp(printerService: PrinterService,viewModel: PrinterViewModel) {
    val context = LocalContext.current
    var isServiceRunning by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf(context.getString(R.string.checking_printer_status)) }
    var savedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    
    // Observe ViewModel refresh trigger for reactive updates
    val refreshTrigger by viewModel.refreshTrigger.collectAsState()
    
    // Function to refresh the list of saved files
    val refreshSavedFiles = {
        savedFiles = FileUtils.getSavedPrintJobs(context)
    }
    
    // Periodically refresh the list of saved files and also when refreshTrigger changes
    LaunchedEffect(refreshTrigger) {
        while (true) {
            refreshSavedFiles()
            delay(1000) // 1 second refresh interval
        }
    }
    
    // Update UI based on actual service status (service lifecycle managed by MainActivity)
    LaunchedEffect(Unit) {
        while (true) {
            val runningService = PrinterForegroundService.getInstance()
            val status = runningService?.getServiceStatus() ?: PrinterService.ServiceStatus.STOPPED

            isServiceRunning = status == PrinterService.ServiceStatus.RUNNING ||
                    status == PrinterService.ServiceStatus.ERROR_SIMULATION

            statusMessage = when (status) {
                PrinterService.ServiceStatus.RUNNING ->
                    context.getString(
                        R.string.printer_service_running_printer_name,
                        runningService?.printerService?.getPrinterName()
                    )

                PrinterService.ServiceStatus.STARTING ->
                    context.getString(R.string.starting_printer_service)

                PrinterService.ServiceStatus.ERROR_SIMULATION ->
                    context.getString(
                        R.string.printer_service_running_error_mode_printer_name,
                        runningService?.printerService?.getPrinterName()
                    )

                PrinterService.ServiceStatus.STOPPED ->
                    context.getString(R.string.printer_service_not_running)
            }
            delay(500) // Poll status every 0.5 seconds
        }
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.delete_all_print_jobs)) },
            text = { Text(stringResource(R.string.delete_all_jobs_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        FileUtils.deleteAllPrintJobs(context)
                        refreshSavedFiles()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text(context.getString(R.string.delete_all))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAllDialog = false }
                ) {
                    Text(context.getString(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        text = PreferenceUtils.getCustomPrinterName(context),
                        style = MaterialTheme.typography.titleLarge
                    ) 
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Status card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = context.getString(R.string.printer_status),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    if (isServiceRunning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.waiting_for_print_jobs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))



                    Button(
                        onClick = {
                            if (isServiceRunning) {
                                PrinterForegroundService.stopService(context)
                                isServiceRunning = false
                                statusMessage = context.getString(R.string.printer_service_not_running)
                            } else {
                                PrinterForegroundService.startService(context)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isServiceRunning)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (isServiceRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(if (isServiceRunning) stringResource(R.string.stop_service) else stringResource(
                            R.string.start_service
                        ))
                    }

                }
            }
            
            // Print jobs header with delete all button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically)
                {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = stringResource(R.string.received_print_jobs),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                
                if (savedFiles.isNotEmpty()) {
                    FilledTonalIconButton(
                        onClick = { showDeleteAllDialog = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = context.getString(R.string.delete_all)
                        )
                    }
                }
            }
            
            if (savedFiles.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier
                                    .size(48.dp)
                                    .padding(bottom = 16.dp)
                            )
                            Text(
                                text = stringResource(R.string.not_print_job_received_yet),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(savedFiles) { file ->
                        PrintJobItem(
                            file = file,
                            onDeleteClick = {
                                FileUtils.deletePrintJob(file)
                                refreshSavedFiles()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PrintJobItem(
    file: File,
    onDeleteClick: () -> Unit
) {
    val context = LocalContext.current
    
    // Determine file format for display and icon
    val fileFormat = when {
        file.name.endsWith(".pdf", ignoreCase = true) -> "PDF"
        file.name.endsWith(".jpg", ignoreCase = true) || 
            file.name.endsWith(".jpeg", ignoreCase = true) -> "JPEG"
        file.name.endsWith(".png", ignoreCase = true) -> "PNG"
        file.name.endsWith(".raw", ignoreCase = true) -> "RAW"
        else -> "DATA"
    }
    
    val fileIcon = Icons.Default.Info
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // File info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = fileIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(40.dp)
                        .padding(end = 12.dp)
                )
                
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = FileUtils.getReadableName(file),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$fileFormat • ${file.length() / 1024} KB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Action buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = {
                        // First analyze the document for debugging
                        val analysis = DocumentDiagnostics.analyzeDocument(file)
                        Log.d("DocumentDebug", "File: ${file.name}")
                        Log.d("DocumentDebug", "Content Type: ${analysis.contentType}")
                        Log.d("DocumentDebug", "Valid Document: ${analysis.isValidDocument}")
                        Log.d("DocumentDebug", "Signatures: ${analysis.signatures}")
                        Log.d("DocumentDebug", "Embedded Docs: ${analysis.embeddedDocuments}")
                        Log.d("DocumentDebug", "Header (first 32 bytes): ${analysis.headerHex}")
                        
                        // Try advanced document converter
                        val success = DocumentConverter.openDocument(context, file)
                        if (!success) {
                            // Fallback to original method
                            FileUtils.openPdfFile(context, file)
                        }
                    },
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("View")
                }
                
                Button(
                    onClick = onDeleteClick,
                    modifier = Modifier.height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(context.getString(R.string.delete))
                }
            }
        }
    }
} 