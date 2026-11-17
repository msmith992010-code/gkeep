package com.gkeep.app.logic

import com.gkeep.app.data.KeptMessage
import com.gkeep.app.data.KeptMessageDao
import com.gkeep.app.data.SenderDao
import com.gkeep.app.data.SenderEntity
import com.gkeep.app.data.isAutoDeleted
import com.gkeep.app.gmail.Email
import com.gkeep.app.gmail.GmailClient

/** A sender is auto-deleted once you've deleted MORE than this many of their emails. */
const val AUTO_DELETE_AFTER = 10

private const val DAY_MS = 24 * 60 * 60 * 1000L

/**
 * GKeep's rules, shared by the screen and the background sync:
 *  - Tapping Keep leaves the email (unread) in Gmail and removes it from GKeep's list.
 *  - Keeping an email remembers its sender: their future emails start marked "Keep",
 *    and their deletion count starts again from zero.
 *  - Emails not marked "Keep" are moved to Trash when you press Delete.
 *  - Deleting a sender's emails more than [AUTO_DELETE_AFTER] times auto-deletes that sender
 *    (a Gmail filter trashes their mail, and GKeep also trashes any that slip into the inbox).
 *  - A kept sender is never auto-deleted.
 */
class InboxManager(
    private val gmail: GmailClient,
    private val dao: SenderDao,
    private val keptDao: KeptMessageDao,
) {

    data class LoadResult(
        val emails: List<Email>,
        val senders: Map<String, SenderEntity>,
        val autoTrashed: Int,
    )

    data class ApplyResult(val trashed: Int, val kept: Int, val newlyBlocked: List<String>)

    /**
     * Inbox mail from the last [days] days, minus emails you've already kept.
     * Mail from auto-deleted senders is trashed and left out.
     */
    suspend fun loadRecent(days: Int): LoadResult {
        val emails = gmail.recentInbox(days)
        keptDao.prune(System.currentTimeMillis() - 7 * DAY_MS) // older than the 5-day max
        val alreadyKept = keptDao.allIds().toSet()
        val senders = dao.all().associateBy { it.email }
        val (blocked, visible) = emails.partition { senders[it.fromEmail]?.isAutoDeleted == true }
        gmail.trash(blocked.map { it.id })
        return LoadResult(visible.filter { it.id !in alreadyKept }, senders, blocked.size)
    }

    /**
     * Keep one email right away: it's hidden from the list and its sender is remembered.
     * Returns the sender's previous state so the action can be undone.
     */
    suspend fun keep(email: Email): SenderEntity? {
        val previous = dao.get(email.fromEmail)
        dao.upsert(markKept(previous ?: SenderEntity(email.fromEmail), email.fromName))
        keptDao.upsertAll(listOf(KeptMessage(email.id, System.currentTimeMillis())))
        return previous
    }

    suspend fun undoKeep(email: Email, previous: SenderEntity?) {
        keptDao.delete(email.id)
        if (previous == null) dao.delete(email.fromEmail) else dao.upsert(previous)
    }

    /** Trash every email in [emails] that isn't in [keepIds], and update sender memory. */
    suspend fun apply(emails: List<Email>, keepIds: Set<String>): ApplyResult {
        val (kept, toTrash) = emails.partition { it.id in keepIds }
        gmail.trash(toTrash.map { it.id })

        val senders = dao.all().associateBy { it.email }.toMutableMap()
        val changed = mutableSetOf<String>()

        // Keeps first, so a newly kept sender's count is reset before this batch's deletions.
        kept.forEach { e ->
            senders[e.fromEmail] = markKept(senders[e.fromEmail] ?: SenderEntity(e.fromEmail), e.fromName)
            changed += e.fromEmail
        }
        val now = System.currentTimeMillis()
        keptDao.upsertAll(kept.map { KeptMessage(it.id, now) })

        val newlyBlocked = mutableListOf<String>()
        toTrash.groupBy { it.fromEmail }.forEach { (address, list) ->
            val s = senders[address] ?: SenderEntity(address)
            val count = s.deleteCount + list.size
            val block = !s.keep && !s.blocked && count > AUTO_DELETE_AFTER
            if (block) newlyBlocked += address
            senders[address] = s.copy(
                displayName = list.first().fromName,
                deleteCount = count,
                blocked = s.blocked || block,
            )
            changed += address
        }
        dao.upsertAll(changed.mapNotNull { senders[it] })

        // Gmail-side filter so their mail is deleted even when GKeep isn't running.
        newlyBlocked.forEach { address ->
            val filterId = runCatching { gmail.createTrashFilter(address) }.getOrNull()
            if (filterId != null) dao.upsert(senders.getValue(address).copy(filterId = filterId))
        }
        return ApplyResult(toTrash.size, kept.size, newlyBlocked)
    }

    /** Becoming a kept sender restarts the deletion count from zero. */
    private fun markKept(s: SenderEntity, name: String): SenderEntity =
        if (s.keep) s.copy(displayName = name)
        else s.copy(displayName = name, keep = true, deleteCount = 0)

    suspend fun removeKeep(email: String) {
        dao.get(email)?.let { dao.upsert(it.copy(keep = false)) }
    }

    suspend fun unblock(email: String) {
        val s = dao.get(email) ?: return
        s.filterId?.let { gmail.deleteFilter(it) }
        dao.upsert(s.copy(blocked = false, deleteCount = 0, filterId = null))
    }
}
