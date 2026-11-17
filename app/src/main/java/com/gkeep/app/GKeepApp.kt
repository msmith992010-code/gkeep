package com.gkeep.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.gkeep.app.auth.GmailAuth
import com.gkeep.app.data.AppDatabase
import com.gkeep.app.data.Prefs
import com.gkeep.app.gmail.GmailClient
import com.gkeep.app.logic.InboxManager
import com.gkeep.app.work.Notifier
import com.gkeep.app.work.Scheduler

class GKeepApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifier.createChannels(this)
        if (graph.prefs.accountEmail != null) Scheduler.schedule(this)
    }
}

/** Simple manual dependency container. */
class AppGraph(context: Context) {
    val prefs = Prefs(context)
    val db = Room.databaseBuilder(context, AppDatabase::class.java, "gkeep.db")
        .addMigrations(AppDatabase.MIGRATION_1_2)
        .build()
    val auth = GmailAuth(context, prefs)
    val gmail = GmailClient { auth.token() }
    val manager = InboxManager(gmail, db.senders(), db.keptMessages())
}
