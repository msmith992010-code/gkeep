package com.gkeep.app.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gkeep.app.GKeepApp
import com.gkeep.app.MainActivity
import com.gkeep.app.R
import com.gkeep.app.auth.NeedsConsentException
import com.gkeep.app.gmail.Email
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs about every 15 minutes (Android's minimum): trashes mail from auto-deleted senders
 * and, if enabled, notifies you about new email.
 */
class InboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as GKeepApp).graph
        val prefs = graph.prefs
        if (prefs.accountEmail == null) return Result.success()

        return try {
            val result = graph.manager.loadRecent()
            val last = prefs.lastNotifiedAt
            if (prefs.notificationsEnabled) {
                val fresh = result.emails.filter { it.receivedAt > last && it.unread }
                val keptSenders = result.senders.values.filter { it.keep }.map { it.email }.toSet()
                val relevant =
                    if (prefs.notifyKeptOnly) fresh.filter { it.fromEmail in keptSenders } else fresh
                if (relevant.isNotEmpty()) {
                    Notifier.showNewMail(applicationContext, relevant, relevant.count { it.fromEmail in keptSenders })
                }
            }
            result.emails.maxOfOrNull { it.receivedAt }?.let { if (it > last) prefs.lastNotifiedAt = it }
            Result.success()
        } catch (e: NeedsConsentException) {
            Notifier.showSignInNeeded(applicationContext)
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        } catch (e: Exception) {
            Result.success() // try again next period
        }
    }
}

object Scheduler {
    private const val WORK_NAME = "gkeep-sync"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<InboxWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

object Notifier {
    private const val CHANNEL_MAIL = "new_mail"
    private const val CHANNEL_ACCOUNT = "account"
    private const val ID_MAIL = 1
    private const val ID_ACCOUNT = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MAIL, "New email", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ACCOUNT, "Account", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun showNewMail(context: Context, emails: List<Email>, fromKept: Int) {
        val title = if (emails.size == 1) emails[0].fromName else "${emails.size} new emails"
        val text = when {
            emails.size == 1 -> emails[0].subject
            fromKept > 0 -> "$fromKept from kept senders · tap to sort"
            else -> "Tap to choose what to keep"
        }
        val style = NotificationCompat.InboxStyle()
        emails.take(5).forEach { style.addLine("${it.fromName}: ${it.subject}") }
        if (emails.size > 5) style.setSummaryText("+${emails.size - 5} more")

        val notification = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(if (emails.size > 1) style else NotificationCompat.BigTextStyle().bigText(text))
            .setNumber(emails.size)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        post(context, ID_MAIL, notification)
    }

    fun showSignInNeeded(context: Context) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ACCOUNT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("GKeep needs you to sign in again")
            .setContentText("Open GKeep to reconnect Gmail.")
            .setContentIntent(openApp(context))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        post(context, ID_ACCOUNT, notification)
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
