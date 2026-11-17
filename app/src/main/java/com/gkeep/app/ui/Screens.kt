package com.gkeep.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gkeep.app.data.SenderEntity
import com.gkeep.app.data.isAutoDeleted
import com.gkeep.app.gmail.Email
import com.gkeep.app.logic.AUTO_DELETE_AFTER
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------- Theme ----------

@Composable
fun GKeepTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------- Sign in ----------

@Composable
fun SignInScreen(loading: Boolean, message: String?, onSignIn: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("GKeep", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "See the last 24 hours of email, tap Keep on what matters, and clear the rest out of your Gmail inbox.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            if (loading) CircularProgressIndicator()
            else Button(onClick = onSignIn) { Text("Connect Gmail") }
            message?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }
}

// ---------- Inbox ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    state: InboxUiState,
    onToggleKeep: (String) -> Unit,
    onRefresh: () -> Unit,
    onCleanUp: () -> Unit,
    onOpenSenders: () -> Unit,
    onOpenSettings: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf(false) }
    val toDelete = state.emails.count { it.id !in state.keepIds }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GKeep")
                        Text(
                            "Last 24 hours · ${state.emails.size} in inbox",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSenders) { Icon(Icons.Default.Person, "Senders") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        bottomBar = {
            if (state.emails.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${state.keepIds.size} keep · $toDelete delete",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = { confirm = true },
                            enabled = toDelete > 0 && !state.working && !state.loading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) { Text("Delete $toDelete") }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (state.working) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                if (state.emails.isEmpty() && state.loadedOnce && !state.loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "Nothing new in the last 24 hours.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(state.emails, key = { it.id }) { email ->
                    EmailRow(
                        email = email,
                        kept = email.id in state.keepIds,
                        autoKept = email.fromEmail in state.keptSenders,
                        onToggle = { onToggleKeep(email.id) },
                    )
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete $toDelete emails?") },
            text = {
                Text("They'll move to Gmail's Trash (recoverable for 30 days). Kept emails stay unread in your inbox.")
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; onCleanUp() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmailRow(email: Email, kept: Boolean, autoKept: Boolean, onToggle: () -> Unit) {
    val bg = if (kept) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent
    val weight = if (email.unread) FontWeight.Bold else FontWeight.Normal
    ListItem(
        modifier = Modifier.clickable(onClick = onToggle),
        colors = ListItemDefaults.colors(containerColor = bg),
        overlineContent = {
            Text(formatTime(email.receivedAt) + if (autoKept) " · kept sender" else "")
        },
        headlineContent = {
            Text(email.fromName, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(
                    email.subject,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (email.unread) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    email.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            FilterChip(
                selected = kept,
                onClick = onToggle,
                label = { Text("Keep") },
                leadingIcon = if (kept) {
                    { Icon(Icons.Default.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                } else null,
            )
        },
    )
    HorizontalDivider()
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

private fun formatTime(millis: Long): String {
    val dt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val prefix = if (dt.toLocalDate() == LocalDate.now()) "" else "Yesterday "
    return prefix + dt.format(timeFmt)
}

// ---------- Senders ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendersScreen(
    senders: List<SenderEntity>,
    onBack: () -> Unit,
    onRemoveKeep: (String) -> Unit,
    onUnblock: (String) -> Unit,
) {
    val kept = senders.filter { it.keep }
    val blocked = senders.filter { it.isAutoDeleted }
    val watching = senders.filter { !it.keep && !it.blocked && it.deleteCount > 0 }
        .sortedByDescending { it.deleteCount }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Senders") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            section("Kept senders", "Their new emails start marked Keep.", kept) { s ->
                SenderRow(s, s.email, "Remove") { onRemoveKeep(s.email) }
            }
            section("Auto-deleted", "Their emails go straight to Trash.", blocked) { s ->
                val how = if (s.filterId != null) "Gmail filter on" else "Deleted by GKeep when it syncs"
                SenderRow(s, "${s.email} · $how", "Unblock") { onUnblock(s.email) }
            }
            section(
                "Getting close",
                "Auto-deleted after more than $AUTO_DELETE_AFTER deletions.",
                watching,
            ) { s ->
                SenderRow(s, "${s.email} · deleted ${s.deleteCount}×", null) {}
            }
        }
    }
}

private fun LazyListScope.section(
    title: String,
    hint: String,
    list: List<SenderEntity>,
    row: @Composable (SenderEntity) -> Unit,
) {
    item(key = "header-$title") {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (list.isEmpty()) {
        item(key = "empty-$title") {
            Text(
                "None yet",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        items(list, key = { "$title-${it.email}" }) { row(it) }
    }
}

@Composable
private fun SenderRow(s: SenderEntity, supporting: String, action: String?, onAction: () -> Unit) {
    ListItem(
        headlineContent = { Text(s.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(supporting, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = if (action != null) {
            { TextButton(onClick = onAction) { Text(action) } }
        } else null,
    )
}

// ---------- Settings ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: InboxUiState,
    onNotifications: (Boolean) -> Unit,
    onNotifyKeptOnly: (Boolean) -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onNotifications(granted)
    }
    fun toggleNotifications(on: Boolean) {
        val needsPermission = on && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else onNotifications(on)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text("Notifications") },
                supportingContent = { Text("Checks for new email about every 15 minutes") },
                trailingContent = {
                    Switch(checked = state.notificationsEnabled, onCheckedChange = ::toggleNotifications)
                },
            )
            ListItem(
                headlineContent = { Text("Only from kept senders") },
                supportingContent = { Text("Ignore new email from everyone else") },
                trailingContent = {
                    Switch(
                        checked = state.notifyKeptOnly,
                        onCheckedChange = onNotifyKeptOnly,
                        enabled = state.notificationsEnabled,
                    )
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("How GKeep works") },
                supportingContent = {
                    Text(
                        "• Tap Keep on emails you want to stay (unread) in your inbox.\n" +
                            "• Delete moves everything else to Gmail's Trash.\n" +
                            "• Keeping an email means future emails from that sender start marked Keep.\n" +
                            "• Delete a sender's emails more than $AUTO_DELETE_AFTER times and GKeep auto-deletes them."
                    )
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Signed in as") },
                supportingContent = { Text(state.account ?: "-") },
            )
            OutlinedButton(onClick = onSignOut, modifier = Modifier.padding(16.dp)) { Text("Sign out") }
        }
    }
}
