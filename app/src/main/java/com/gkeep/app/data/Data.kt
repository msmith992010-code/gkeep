package com.gkeep.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Everything GKeep remembers about one sender. */
@Entity(tableName = "senders")
data class SenderEntity(
    @PrimaryKey val email: String,
    val displayName: String = email,
    /** New emails from this sender start marked "Keep". */
    val keep: Boolean = false,
    /** How many of their emails you've deleted with GKeep. */
    val deleteCount: Int = 0,
    /** Auto-deleted: all their mail goes to Trash. */
    val blocked: Boolean = false,
    /** Id of the Gmail filter that trashes their mail, if it was created. */
    val filterId: String? = null,
)

/** All their mail goes to Trash (a kept sender is never auto-deleted). */
val SenderEntity.isAutoDeleted: Boolean get() = blocked && !keep

@Dao
interface SenderDao {
    @Query("SELECT * FROM senders ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<SenderEntity>>

    @Query("SELECT * FROM senders")
    suspend fun all(): List<SenderEntity>

    @Query("SELECT * FROM senders WHERE email = :email")
    suspend fun get(email: String): SenderEntity?

    @Upsert
    suspend fun upsert(sender: SenderEntity)

    @Upsert
    suspend fun upsertAll(senders: List<SenderEntity>)

    @Query("DELETE FROM senders WHERE email = :email")
    suspend fun delete(email: String)
}

/** An email you kept. Kept emails stay in Gmail but are hidden from GKeep's list. */
@Entity(tableName = "kept_messages")
data class KeptMessage(@PrimaryKey val id: String, val keptAt: Long)

@Dao
interface KeptMessageDao {
    @Query("SELECT id FROM kept_messages")
    suspend fun allIds(): List<String>

    @Upsert
    suspend fun upsertAll(messages: List<KeptMessage>)

    @Query("DELETE FROM kept_messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM kept_messages WHERE keptAt < :before")
    suspend fun prune(before: Long)
}

@Database(entities = [SenderEntity::class, KeptMessage::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun senders(): SenderDao
    abstract fun keptMessages(): KeptMessageDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `kept_messages` " +
                        "(`id` TEXT NOT NULL, `keptAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }
    }
}

/** Small settings store. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("gkeep", Context.MODE_PRIVATE)

    var accountEmail: String?
        get() = sp.getString("account", null)
        set(v) = sp.edit().putString("account", v).apply()

    var notificationsEnabled: Boolean
        get() = sp.getBoolean("notifications", true)
        set(v) = sp.edit().putBoolean("notifications", v).apply()

    var notifyKeptOnly: Boolean
        get() = sp.getBoolean("notify_kept_only", false)
        set(v) = sp.edit().putBoolean("notify_kept_only", v).apply()

    /** How many days of email to show: 1 to 5. */
    var lookbackDays: Int
        get() = sp.getInt("lookback_days", 1).coerceIn(1, 5)
        set(v) = sp.edit().putInt("lookback_days", v.coerceIn(1, 5)).apply()

    /** Newest email time we've already shown or notified about. */
    var lastNotifiedAt: Long
        get() = sp.getLong("last_notified", 0L)
        set(v) = sp.edit().putLong("last_notified", v).apply()
}
