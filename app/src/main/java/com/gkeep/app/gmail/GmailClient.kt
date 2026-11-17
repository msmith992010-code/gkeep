package com.gkeep.app.gmail

import androidx.core.text.HtmlCompat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

private interface GmailApi {
    @GET("users/me/profile")
    suspend fun profile(@Header("Authorization") auth: String): Profile

    @GET("users/me/messages")
    suspend fun listMessages(
        @Header("Authorization") auth: String,
        @Query("q") query: String,
        @Query("maxResults") maxResults: Int,
        @Query("pageToken") pageToken: String?,
    ): MessageList

    @GET("users/me/messages/{id}")
    suspend fun getMessage(
        @Header("Authorization") auth: String,
        @Path("id") id: String,
        @Query("format") format: String = "metadata",
        @Query("metadataHeaders") headers: List<String> = listOf("From", "Subject"),
    ): Message

    @POST("users/me/messages/{id}/trash")
    suspend fun trash(@Header("Authorization") auth: String, @Path("id") id: String): MessageRef

    @POST("users/me/settings/filters")
    suspend fun createFilter(@Header("Authorization") auth: String, @Body filter: Filter): Filter

    @DELETE("users/me/settings/filters/{id}")
    suspend fun deleteFilter(@Header("Authorization") auth: String, @Path("id") id: String): Response<Unit>
}

/**
 * Thin wrapper around the Gmail REST API.
 * Reading metadata never marks a message as read, so kept emails stay unread.
 */
class GmailClient(private val tokenProvider: suspend () -> String) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val api: GmailApi = Retrofit.Builder()
        .baseUrl("https://gmail.googleapis.com/gmail/v1/")
        .client(OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GmailApi::class.java)

    private suspend fun auth() = "Bearer ${tokenProvider()}"

    suspend fun profileEmail(): String = api.profile(auth()).emailAddress.lowercase()

    /** Messages currently in the inbox that arrived in the last [days] days, newest first. */
    suspend fun recentInbox(days: Int): List<Email> {
        val cutoff = System.currentTimeMillis() - days * DAY_MS
        val auth = auth()
        val refs = mutableListOf<MessageRef>()
        var page: String? = null
        do {
            val result = api.listMessages(auth, "in:inbox newer_than:${days}d", 100, page)
            refs += result.messages
            page = result.nextPageToken
        } while (page != null && refs.size < MAX_MESSAGES)

        val messages = inBatches(refs.map { it.id }) { id ->
            try {
                api.getMessage(auth, id)
            } catch (e: HttpException) {
                if (e.code() == 404) null else throw e // deleted in the meantime
            }
        }.filterNotNull()

        return messages.map { it.toEmail() }
            .filter { it.receivedAt >= cutoff }
            .sortedByDescending { it.receivedAt }
    }

    /** Moves messages to Gmail's Trash (auto-emptied by Gmail after 30 days). */
    suspend fun trash(ids: List<String>) {
        if (ids.isEmpty()) return
        val auth = auth()
        inBatches(ids) { id ->
            try {
                api.trash(auth, id)
            } catch (e: HttpException) {
                if (e.code() != 404) throw e
            }
        }
    }

    /** Creates a Gmail filter that sends all future mail from [from] straight to Trash. */
    suspend fun createTrashFilter(from: String): String? =
        api.createFilter(
            auth(),
            Filter(
                criteria = FilterCriteria(from = from),
                action = FilterAction(addLabelIds = listOf("TRASH")),
            ),
        ).id

    suspend fun deleteFilter(id: String) {
        api.deleteFilter(auth(), id)
    }

    private suspend fun <T> inBatches(ids: List<String>, call: suspend (String) -> T): List<T> =
        coroutineScope {
            ids.chunked(BATCH).flatMap { chunk -> chunk.map { async { call(it) } }.awaitAll() }
        }

    private fun Message.toEmail(): Email {
        val headers = payload?.headers.orEmpty()
        fun header(name: String) =
            headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value.orEmpty()
        val (name, address) = parseFrom(header("From"))
        return Email(
            id = id,
            fromEmail = address,
            fromName = name,
            subject = header("Subject").ifBlank { "(no subject)" },
            snippet = HtmlCompat.fromHtml(snippet, HtmlCompat.FROM_HTML_MODE_LEGACY).toString(),
            receivedAt = internalDate?.toLongOrNull() ?: 0L,
            unread = "UNREAD" in labelIds,
        )
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val BATCH = 10
        private const val MAX_MESSAGES = 1000

        /** "Jane Doe <Jane@Example.com>" -> ("Jane Doe", "jane@example.com") */
        fun parseFrom(raw: String): Pair<String, String> {
            val match = Regex("<([^>]+)>").find(raw)
            return if (match != null) {
                val address = match.groupValues[1].trim().lowercase()
                val name = raw.substring(0, match.range.first).trim().trim('"').ifBlank { address }
                name to address
            } else {
                val address = raw.trim().lowercase()
                address to address
            }
        }
    }
}
