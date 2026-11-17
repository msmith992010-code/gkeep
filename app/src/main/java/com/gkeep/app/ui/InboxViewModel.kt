package com.gkeep.app.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gkeep.app.GKeepApp
import com.gkeep.app.auth.NeedsConsentException
import com.gkeep.app.data.SenderEntity
import com.gkeep.app.gmail.Email
import com.gkeep.app.work.Scheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

data class InboxUiState(
    val account: String? = null,
    val emails: List<Email> = emptyList(),
    val keepIds: Set<String> = emptySet(),
    val keptSenders: Set<String> = emptySet(),
    val loading: Boolean = false,
    val working: Boolean = false,
    val loadedOnce: Boolean = false,
    val message: String? = null,
    val notificationsEnabled: Boolean = true,
    val notifyKeptOnly: Boolean = false,
)

class InboxViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = (app as GKeepApp).graph
    private val prefs = graph.prefs

    private val _state = MutableStateFlow(
        InboxUiState(
            account = prefs.accountEmail,
            notificationsEnabled = prefs.notificationsEnabled,
            notifyKeptOnly = prefs.notifyKeptOnly,
        )
    )
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    val senders: StateFlow<List<SenderEntity>> = graph.db.senders().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _consent = MutableStateFlow<PendingIntent?>(null)
    val consent: StateFlow<PendingIntent?> = _consent.asStateFlow()

    private var awaitingConsent = false
    private var job: Job? = null
    /** Keep/delete choices you've made by hand on the current list (email id -> keep). */
    private val overrides = mutableMapOf<String, Boolean>()

    fun signIn() = launchTask(loading = true) {
        val email = graph.gmail.profileEmail()
        prefs.accountEmail = email
        prefs.lastNotifiedAt = System.currentTimeMillis()
        Scheduler.schedule(getApplication())
        _state.update { it.copy(account = email) }
        load()
    }

    fun refresh() {
        if (_state.value.account == null || awaitingConsent || job?.isActive == true) return
        launchTask(loading = true) { load() }
    }

    fun toggleKeep(id: String) {
        _state.update { s ->
            val keep = id !in s.keepIds
            overrides[id] = keep
            s.copy(keepIds = if (keep) s.keepIds + id else s.keepIds - id)
        }
    }

    /** Trash everything not marked Keep. */
    fun cleanUp() {
        if (job?.isActive == true) return
        launchTask(working = true) {
            val s = _state.value
            val result = graph.manager.apply(s.emails, s.keepIds)
            overrides.clear()
            load()
            val msg = buildString {
                append("Deleted ${result.trashed}, kept ${result.kept}.")
                if (result.newlyBlocked.isNotEmpty()) {
                    append(" Now auto-deleting: ${result.newlyBlocked.joinToString()}")
                }
            }
            _state.update { it.copy(message = msg) }
        }
    }

    fun removeKeep(email: String) {
        viewModelScope.launch {
            graph.manager.removeKeep(email)
            _state.update { it.copy(keptSenders = it.keptSenders - email) }
        }
    }

    fun unblock(email: String) = launchTask { graph.manager.unblock(email) }

    fun setNotifications(on: Boolean) {
        prefs.notificationsEnabled = on
        _state.update { it.copy(notificationsEnabled = on) }
    }

    fun setNotifyKeptOnly(on: Boolean) {
        prefs.notifyKeptOnly = on
        _state.update { it.copy(notifyKeptOnly = on) }
    }

    fun signOut() {
        job?.cancel()
        Scheduler.cancel(getApplication())
        prefs.accountEmail = null
        graph.auth.clear()
        overrides.clear()
        _state.value = InboxUiState(
            notificationsEnabled = prefs.notificationsEnabled,
            notifyKeptOnly = prefs.notifyKeptOnly,
        )
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun consentShown() {
        _consent.value = null
    }

    fun onConsentResult(ok: Boolean, data: Intent?) {
        awaitingConsent = false
        if (!ok) {
            _state.update { it.copy(message = "GKeep needs Gmail access to work.") }
            return
        }
        try {
            graph.auth.handleConsentResult(data)
        } catch (e: Exception) {
            _state.update { it.copy(message = "Google sign-in failed: ${e.message}") }
            return
        }
        if (_state.value.account == null) signIn() else refresh()
    }

    private suspend fun load() {
        val r = graph.manager.loadRecent()
        val kept = r.senders.values.filter { it.keep }.map { it.email }.toSet()
        overrides.keys.retainAll(r.emails.map { it.id }.toSet())
        val keepIds = r.emails
            .filter { overrides[it.id] ?: (it.fromEmail in kept) }
            .map { it.id }
            .toSet()
        r.emails.maxOfOrNull { it.receivedAt }?.let {
            if (it > prefs.lastNotifiedAt) prefs.lastNotifiedAt = it
        }
        _state.update {
            it.copy(
                emails = r.emails,
                keepIds = keepIds,
                keptSenders = kept,
                loadedOnce = true,
                message = if (r.autoTrashed > 0) {
                    "Auto-deleted ${r.autoTrashed} from blocked senders"
                } else it.message,
            )
        }
    }

    private fun launchTask(
        loading: Boolean = false,
        working: Boolean = false,
        block: suspend () -> Unit,
    ) {
        job = viewModelScope.launch {
            _state.update { it.copy(loading = it.loading || loading, working = it.working || working) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsConsentException) {
                awaitingConsent = true
                _consent.value = e.pendingIntent
            } catch (e: HttpException) {
                if (e.code() == 401) graph.auth.clear()
                _state.update { it.copy(message = "Gmail error ${e.code()}. Pull down to retry.") }
            } catch (e: IOException) {
                _state.update { it.copy(message = "Couldn't reach Gmail. Check your connection.") }
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Something went wrong") }
            } finally {
                _state.update { it.copy(loading = false, working = false) }
            }
        }
    }
}
