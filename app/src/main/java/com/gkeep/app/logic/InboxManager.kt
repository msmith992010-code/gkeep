package com.gkeep.app.logic

import com.gkeep.app.data.SenderDao
import com.gkeep.app.data.SenderEntity
import com.gkeep.app.data.isAutoDeleted
import com.gkeep.app.gmail.Email
import com.gkeep.app.gmail.GmailClient

/** A sender is auto-deleted once you've deleted MORE than this many of their emails. */
const val AUTO_DELETE_AFTER = 10

/**
 * GKeep's rules, shared by the screen and the background sync:
 *  - Emails you don't mark "Keep" are moved to Trash when you clean up.
 *  - Keeping an email remembers its sender; their future emails start marked "Keep".
 *  - Deleting a sender's emails more than [AUTO_DELETE_AFTER] times auto-deletes that sender
 *    (a Gmail filter trashes their mail, and GKeep also trashes any that slip into the inbox).
 *  - A kept sender is never auto-deleted.
 */
class InboxManager(private val gmail: GmailClient, private val dao: SenderDao) {

    data class LoadResult(
        val emails: List<Email>,
        val senders: Map<String, SenderEntity>,
        val autoTrashed: Int,
    )

    data class ApplyResult(val trashed: Int, val kept: Int, val newlyBlocked: List<String>)

    /** Last 24h of inbox mail. Mail from auto-deleted senders is trashed and left out. */
    suspend fun loadRecent(): LoadResult {
        val emails = gmail.recentInbox()
        val senders = dao.all().associateBy { it.email }
        val (blocked, visible) = emails.partition { senders[it.fromEmail]?.isAutoDeleted == true }
        gmail.trash(blocked.map { it.id })
        return LoadResult(visible, senders, blocked.size)
    }

    /** Trash every email in [emails] that isn't in [keepIds], and update sender memory. */
    suspend fun apply(emails: List<Email>, keepIds: Set<String>): ApplyResult {
        val (kept, toTrash) = emails.partition { it.id in keepIds }
        gmail.trash(toTrash.map { it.id })

        val senders = dao.all().associateBy { it.email }.toMutableMap()
        val changed = mutableSetOf<String>()

        kept.forEach { e ->
            val s = senders[e.fromEmail] ?: SenderEntity(e.fromEmail)
            senders[e.fromEmail] = s.copy(displayName = e.fromName, keep = true)
            changed += e.fromEmail
        }

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

    suspend fun removeKeep(email: String) {
        dao.get(email)?.let { dao.upsert(it.copy(keep = false)) }
    }

    suspend fun unblock(email: String) {
        val s = dao.get(email) ?: return
        s.filterId?.let { gmail.deleteFilter(it) }
        dao.upsert(s.copy(blocked = false, deleteCount = 0, filterId = null))
    }
}
