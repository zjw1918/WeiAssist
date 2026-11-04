package com.lanqi.weiassist.channels

/**
 * A messaging app the user can talk to the assistant through (Telegram first). A channel only moves
 * messages: who may talk, which conversation a chat goes to and what is said are the gateway's
 * business (`:agent`, Gateway.kt). Every call reaches only out from the phone, so no server is needed.
 */
interface Channel {
    /** Short and stable: part of every [ChatRef] and saved binding ("telegram"). */
    val id: String

    /** How [receive] is doing. */
    val link: kotlinx.coroutines.flow.StateFlow<Link>

    /** Receive messages until cancelled, reconnecting by itself; fatal problems end it with a [ChannelException]. */
    suspend fun receive(onMessage: suspend (Inbound) -> Unit)

    /** Send [text] to [chat]; returns the new message's id. */
    suspend fun send(chat: ChatRef, text: Rich): String

    /** Replace a message this channel sent. */
    suspend fun edit(chat: ChatRef, message: String, text: Rich)

    /** Show "typing…" in [chat] for a few seconds. */
    suspend fun typing(chat: ChatRef)
}

/** A channel's connection while it receives. */
sealed interface Link {
    data object Off : Link
    data object Connecting : Link
    data object Online : Link
    /** It lost the connection ([problem]) and tries again shortly. */
    data class Retrying(val problem: String) : Link
}

/** A chat in a channel: [chat] is the channel's own id for it. */
data class ChatRef(val channel: String, val chat: String) {
    /** For saving: "telegram:12345". */
    val key get() = "$channel:$chat"
}

/** Who sent a message: [id] is the channel's own user id, [name] what to show for them. */
data class Sender(val id: String, val name: String)

/**
 * A message to the assistant. [private]: a one-to-one chat with the bot (groups are not supported yet).
 * [unsupported]: it carried something other than text (a photo, a file, a voice note), not handled yet.
 */
data class Inbound(
    val chat: ChatRef,
    val from: Sender,
    val text: String,
    val private: Boolean,
    val unsupported: Boolean = false,
)

/** A message in the channel's own markup ([markup]), and the same as plain text in case the markup is refused. */
data class Rich(val markup: String, val plain: String)

/**
 * The channel can't go on: [fatal] = it won't work until the user changes something (a revoked token,
 * another program reading the same bot); otherwise it is retried.
 */
class ChannelException(message: String, val fatal: Boolean = false) : Exception(message)
