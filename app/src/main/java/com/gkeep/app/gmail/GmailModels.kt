package com.gkeep.app.gmail

import kotlinx.serialization.Serializable

// ---- Raw Gmail API JSON ----

@Serializable
data class Profile(val emailAddress: String)

@Serializable
data class MessageList(
    val messages: List<MessageRef> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class MessageRef(val id: String, val threadId: String? = null)

@Serializable
data class Message(
    val id: String,
    val threadId: String? = null,
    val labelIds: List<String> = emptyList(),
    val snippet: String = "",
    val internalDate: String? = null,
    val payload: Payload? = null,
)

@Serializable
data class Payload(val headers: List<Header> = emptyList())

@Serializable
data class Header(val name: String, val value: String)

@Serializable
data class Filter(
    val id: String? = null,
    val criteria: FilterCriteria? = null,
    val action: FilterAction? = null,
)

@Serializable
data class FilterCriteria(val from: String? = null)

@Serializable
data class FilterAction(
    val addLabelIds: List<String>? = null,
    val removeLabelIds: List<String>? = null,
)

// ---- What the app works with ----

data class Email(
    val id: String,
    val fromEmail: String,   // lower-cased address, used as the sender key
    val fromName: String,
    val subject: String,
    val snippet: String,
    val receivedAt: Long,    // epoch millis
    val unread: Boolean,
)
