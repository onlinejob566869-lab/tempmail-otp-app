package com.tempmail.otp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tempmail.otp.data.EmailSummary
import com.tempmail.otp.data.InboxMonitor
import com.tempmail.otp.service.OtpPollingService
import com.tempmail.otp.ui.InboxEntry
import com.tempmail.otp.ui.MainViewModel
import com.tempmail.otp.ui.UiState
import com.tempmail.otp.util.AppVisibility
import com.tempmail.otp.util.ClipboardHelper
import com.tempmail.otp.util.InboxClock
import com.tempmail.otp.util.NotificationHelper
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppScreen()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.isForeground = true
    }

    override fun onStop() {
        AppVisibility.isForeground = false
        super.onStop()
    }
}

@Composable
fun AppScreen(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val inboxes by viewModel.inboxes.collectAsState()
    val activeIndex by viewModel.activeIndex.collectAsState()
    val creationsToday by viewModel.creationsToday.collectAsState()
    val dailyLimit = viewModel.dailyLimit
    val notice by viewModel.notice.collectAsState()
    val states by viewModel.monitorStates.collectAsState()
    val bulk by viewModel.bulkProgress.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        NotificationHelper.createChannel(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "TempMail OTP",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "10 parallel inboxes • real-time OTP",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Bulk creation progress (1..10 inboxes)
        if (bulk != null) {
            val (done, total) = bulk!!
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done.toFloat() / total },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("$done/$total", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Inbox tabs / switcher
        if (inboxes.isNotEmpty()) {
            InboxSwitcher(
                inboxes = inboxes,
                activeIndex = activeIndex,
                states = states,
                creationsToday = creationsToday,
                dailyLimit = dailyLimit,
                onSwitch = { viewModel.switchInbox(it) },
                onAdd = { viewModel.createInbox() },
                onAddTen = { viewModel.createInboxes(10) },
                onExtendAll = { viewModel.extendAll() },
                onDelete = { viewModel.deleteInbox(it) }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Transient message (quota, extend result, expired inbox)
        if (notice != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
                ) {
                    Text(
                        text = notice!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.dismissNotice() }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Dismiss",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Main content
        when (val state = uiState) {
            is UiState.Idle -> {
                IdleContent(viewModel)
            }
            is UiState.Ready -> {
                if (state.inboxes.isNotEmpty() && activeIndex < state.inboxes.size) {
                    val active = state.inboxes[activeIndex]
                    ActiveInboxContent(
                        inbox = active,
                        viewModel = viewModel,
                        context = context
                    )
                }
            }
            is UiState.Error -> {
                if (inboxes.isNotEmpty()) {
                    val active = inboxes[activeIndex]
                    ActiveInboxContent(
                        inbox = active,
                        viewModel = viewModel,
                        context = context
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InboxSwitcher(
    inboxes: List<InboxEntry>,
    activeIndex: Int,
    states: Map<String, InboxMonitor.State>,
    creationsToday: Int,
    dailyLimit: Int,
    onSwitch: (Int) -> Unit,
    onAdd: () -> Unit,
    onAddTen: () -> Unit,
    onExtendAll: () -> Unit,
    onDelete: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // One horizontal row keeps all 10 inboxes reachable instead of hiding them in 2 rows.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(inboxes.size) { index ->
                val inbox = inboxes[index]
                val expired = inbox.isExpired()
                val connected = states[inbox.address]?.connected == true
                FilterChip(
                    selected = index == activeIndex,
                    onClick = { onSwitch(index) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when {
                                    expired -> "⏳"
                                    connected -> "🟢"
                                    else -> "🟡"
                                },
                                fontSize = 10.sp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(inbox.label, fontSize = 12.sp)
                            if (inbox.emails.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${inbox.emails.size}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                )
            }
            item {
                AssistChip(
                    onClick = onAdd,
                    enabled = creationsToday < dailyLimit,
                    label = { Text("+1", fontSize = 12.sp) }
                )
            }
            item {
                AssistChip(
                    onClick = onAddTen,
                    enabled = creationsToday < dailyLimit,
                    label = { Text("+10", fontSize = 12.sp) }
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "$creationsToday/$dailyLimit today • ${inboxes.size} inbox(es)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (inboxes.size > 1) {
                TextButton(onClick = onExtendAll) {
                    Text("Extend all", fontSize = 11.sp)
                }
            }
            IconButton(
                onClick = { onDelete(activeIndex) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete inbox",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun IdleContent(viewModel: MainViewModel) {
    val creationsToday by viewModel.creationsToday.collectAsState()
    val dailyLimit = viewModel.dailyLimit
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Email,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Tap below to generate a temp email.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Aaj: $creationsToday/$dailyLimit inboxes (free tier)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { viewModel.createInbox() },
                enabled = creationsToday < dailyLimit
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Generate Temp Email")
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = { viewModel.createInboxes(10) },
                enabled = creationsToday < dailyLimit
            ) {
                Text("Create 10 inboxes (parallel)", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun ActiveInboxContent(
    inbox: InboxEntry,
    viewModel: MainViewModel,
    context: android.content.Context
) {
    val states by viewModel.monitorStates.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        // Email address card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Active Email",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = inbox.address,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.copyActiveEmail() }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Copy")
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (states[inbox.address]?.connected == true) {
                        "● Live — listening for mail"
                    } else {
                        "○ Connecting…"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (states[inbox.address]?.connected == true) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Expiry countdown + extend (free inboxes live 60 minutes)
                val expiresAt = inbox.expiresAt
                if (expiresAt != null) {
                    var now by remember(inbox.address) { mutableStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(inbox.address) {
                        while (true) {
                            delay(1000L)
                            now = System.currentTimeMillis()
                        }
                    }
                    val remaining = expiresAt - now
                    val expired = remaining <= 0L
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (expired) {
                                "Expired — create a new inbox"
                            } else {
                                "Expires in ${InboxClock.formatRemaining(remaining)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (expired || remaining < 5 * 60 * 1000L) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1f)
                        )
                        if (expired) {
                            TextButton(onClick = { viewModel.createInbox() }) {
                                Text("New inbox", fontSize = 12.sp)
                            }
                        } else {
                            TextButton(onClick = { viewModel.extendInbox(inbox.address) }) {
                                Text("Extend +10 min", fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Last OTP
                if (inbox.lastOtp != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Last OTP: ${inbox.lastOtp}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (inbox.lastOtpFrom != null) {
                                Text(
                                    text = "From: ${inbox.lastOtpFrom}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    ClipboardHelper.copyText(
                                        context, "OTP Code", inbox.lastOtp
                                    )
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Copy OTP")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Background service controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            OtpPollingService.start(context, viewModel.getAllAddresses())
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Start BG", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            OtpPollingService.stop(context)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop BG", fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Inbox emails
        Text(
            text = "Inbox (${inbox.emails.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (inbox.emails.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Watching for emails...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(inbox.emails) { email ->
                    EmailRow(email)
                }
            }
        }
    }
}

@Composable
fun EmailRow(email: EmailSummary) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = email.fromAddress,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                if (email.otpCode != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "OTP: ${email.otpCode}",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = email.subject, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
