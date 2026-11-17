package com.gkeep.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gkeep.app.ui.GKeepTheme
import com.gkeep.app.ui.InboxScreen
import com.gkeep.app.ui.InboxViewModel
import com.gkeep.app.ui.SendersScreen
import com.gkeep.app.ui.SettingsScreen
import com.gkeep.app.ui.SignInScreen

class MainActivity : ComponentActivity() {
    private val vm: InboxViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GKeepTheme { GKeepRoot(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh() // fresh list every time you open the app
    }
}

private enum class Screen { Inbox, Senders, Settings }

@Composable
private fun GKeepRoot(vm: InboxViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val senders by vm.senders.collectAsStateWithLifecycle()
    val consent by vm.consent.collectAsStateWithLifecycle()

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> vm.onConsentResult(result.resultCode == Activity.RESULT_OK, result.data) }

    LaunchedEffect(consent) {
        consent?.let {
            consentLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build())
            vm.consentShown()
        }
    }

    var screen by rememberSaveable { mutableStateOf(Screen.Inbox) }

    if (state.account == null) {
        SignInScreen(loading = state.loading, message = state.message, onSignIn = vm::signIn)
    } else {
        BackHandler(enabled = screen != Screen.Inbox) { screen = Screen.Inbox }
        when (screen) {
            Screen.Inbox -> InboxScreen(
                state = state,
                onToggleKeep = vm::toggleKeep,
                onRefresh = vm::refresh,
                onCleanUp = vm::cleanUp,
                onOpenSenders = { screen = Screen.Senders },
                onOpenSettings = { screen = Screen.Settings },
                onMessageShown = vm::messageShown,
            )
            Screen.Senders -> SendersScreen(
                senders = senders,
                onBack = { screen = Screen.Inbox },
                onRemoveKeep = vm::removeKeep,
                onUnblock = vm::unblock,
            )
            Screen.Settings -> SettingsScreen(
                state = state,
                onNotifications = vm::setNotifications,
                onNotifyKeptOnly = vm::setNotifyKeptOnly,
                onSignOut = { vm.signOut(); screen = Screen.Inbox },
                onBack = { screen = Screen.Inbox },
            )
        }
    }
}
