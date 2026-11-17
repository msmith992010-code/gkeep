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

/** A snackbar message. [canUndo] shows an Undo button for the last Keep. */
data class UiMessage(
    val text: String,
    val canUndo: Boolean = false,
    val id: Long = System.nanoTime(),
)

data class InboxUiState(
    val account: String? = null,
    val emails: List<Email> = emptyList(),
    val keepIds: Set<String> = emptySet(),
    val keptSenders: Set<String> = emptySet(),
    val loading: Boolean = false,
    val working: Boolean = false,
    val loadedOnce: Boolean = false,
    val message: UiMessage? = null,
    val notificationsEnabled: Boolean = true,
    val notifyKeptOnly: Boolean = false,
    val days: Int = 1,
)

class InboxViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = (app as GKeepApp).graph
    private val prefs = graph.prefs

    private val _state = MutableStateFlow(freshState())
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    val senders: StateFlow<List<SenderEntity>> = graph.db.senders().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _consent = MutableStateFlow<PendingIntent?>(null)
    val consent: StateFlow<PendingIntent?> = _consent.asStateFlow()

    private var awaitingConsent = false
    private var job: Job? = null
    /** Choices you've made by hand on the current list (email id -> keep). */
    private val overrides = mutableMapOf<String, Boolean>()

    private data class LastKeep(val email: Email, val previous: SenderEntity?)
    private var lastKeep: LastKeep? = null

    private fun freshState(account: String? = prefs.accountEmail) = InboxUiState(
        account = account,
        notificationsEnabled = prefs.notificationsEnabled,
        notifyKeptOnly = prefs.notifyKeptOnly,
        days = prefs.lookbackDays,
    )

    fun signIn() = launchTask(loading = true) {
        val email = graph.gmail.profileEmail()
        prefs.accountEmail = email
        prefs.lastNotifiedAt = System.currentTimeMillis()
        Scheduler.schedule(getApplication())
        _state.update { it.copy(account = email) }
        load()
    }

    fun refresh(force: Boolean = false) {
        if (_state.value.account == null || awaitingConsent) return
        if (job?.isActive == true) {
            if (!force) return
            job?.cancel()
        }
        launchTask(loading = true) { load() }
    }

    /**
     * Tapping Keep on an email keeps it immediately: it stays unread in Gmail and leaves this list.
     * Tapping a pre-selected (kept-sender) email un-keeps it, so it will be deleted.
     */
    fun toggleKeep(id: String) {
        val s = _state.value
        val email = s.emails.find { it.id == id } ?: return
        if (id in s.keepIds) {
            overrides[id] = false
            _state.update { it.copy(keepIds = it.keepIds - id) }
            return
        }
        overrides.remove(id)
        val kept = s.keptSenders + email.fromEmail
        val emails = s.emails - email
        _state.update {
            it.copy(
                emails = emails,
                keptSenders = kept,
                keepIds = keepIdsFor(emails, kept),
                message = UiMessage("Kept: ${email.fromName}", canUndo = true),
            )
        }
        viewModelScope.launch {
            try {
                lastKeep = LastKeep(email, graph.manager.keep(email))
            } catch (e: Exception) {
                _state.update { it.copy(message = UiMessage("Couldn't save that keep: ${e.message}")) }
            }
        }
    }

    fun undoKeep() {
        val last = lastKeep ?: return
        lastKeep = null
        viewModelScope.launch {
            graph.manager.undoKeep(last.email, last.previous)
            _state.update { s ->
                val kept = if (last.previous?.keep == true) s.keptSenders else s.keptSenders - last.email.fromEmail
                val emails = (s.emails + last.email).sortedByDescending { it.receivedAt }
                s.copy(emails = emails, keptSenders = kept, keepIds = keepIdsFor(emails, kept))
            }
        }
    }

    /** Trash everything not marked Keep. */
    fun cleanUp() {
        if (job?.isActive == true) return
        lastKeep = null
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
            _state.update { it.copy(message = UiMessage(msg)) }
        }
    }

    fun removeKeep(email: String) {
        viewModelScope.launch {
            graph.manager.removeKeep(email)
            _state.update {
                val kept = it.keptSenders - email
                it.copy(keptSenders = kept, keepIds = keepIdsFor(it.emails, kept))
            }
        }
    }

    fun unblock(email: String) = launchTask { graph.manager.unblock(email) }

    fun setDays(days: Int) {
        if (days == prefs.lookbackDays) return
        prefs.lookbackDays = days
        _state.update { it.copy(days = prefs.lookbackDays) }
        refresh(force = true)
    }

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
        lastKeep = null
        _state.value = freshState(account = null)
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun consentShown() {
        _consent.value = null
    }

    fun onConsentResult(ok: Boolean, data: Intent?) {
        awaitingConsent = false
        if (!ok) {
            _state.update { it.copy(message = UiMessage("GKeep needs Gmail access to work.")) }
            return
        }
        try {
            graph.auth.handleConsentResult(data)
        } catch (e: Exception) {
            _state.update { it.copy(message = UiMessage("Google sign-in failed: ${e.message}")) }
            return
        }
        if (_state.value.account == null) signIn() else refresh()
    }

    private fun keepIdsFor(emails: List<Email>, keptSenders: Set<String>): Set<String> =
        emails.filter { overrides[it.id] ?: (it.fromEmail in keptSenders) }.map { it.id }.toSet()

    private suspend fun load() {
        val r = graph.manager.loadRecent(prefs.lookbackDays)
        val kept = r.senders.values.filter { it.keep }.map { it.email }.toSet()
        overrides.keys.retainAll(r.emails.map { it.id }.toSet())
        r.emails.maxOfOrNull { it.receivedAt }?.let {
            if (it > prefs.lastNotifiedAt) prefs.lastNotifiedAt = it
        }
        _state.update {
            it.copy(
                emails = r.emails,
                keepIds = keepIdsFor(r.emails, kept),
                keptSenders = kept,
                loadedOnce = true,
                message = if (r.autoTrashed > 0) {
                    UiMessage("Auto-deleted ${r.autoTrashed} from blocked senders")
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
                _state.update { it.copy(message = UiMessage("Gmail error ${e.code()}. Pull down to retry.")) }
            } catch (e: IOException) {
                _state.update { it.copy(message = UiMessage("Couldn't reach Gmail. Check your connection.")) }
            } catch (e: Exception) {
                _state.update { it.copy(message = UiMessage(e.message ?: "Something went wrong")) }
            } finally {
                // A newer task may have replaced this one (e.g. changing days); leave its spinner alone.
                if (job === coroutineContext[Job]) {
                    _state.update { it.copy(loading = false, working = false) }
                }
            }
        }
    }
}
