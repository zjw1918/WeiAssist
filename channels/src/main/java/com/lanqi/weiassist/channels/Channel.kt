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

    /** Send [text] to [chat], with [buttons] under it; returns the new message's id. */
    suspend fun send(chat: ChatRef, text: Rich, buttons: List<Button> = emptyList()): String

    /** Replace a message this channel sent (its buttons too: none given, none left). */
    suspend fun edit(chat: ChatRef, message: String, text: Rich, buttons: List<Button> = emptyList())

    /** Answer a [Press] (the app shows it was received); [note] is shown briefly. */
    suspend fun acknowledge(press: Press, note: String? = null)

    /** Fetch a file someone sent ([Inbound.files]) into [dest]. */
    suspend fun download(file: RemoteFile, dest: java.io.File)

    /** Send a file to [chat] (an image as a picture where the channel can); returns the message's id. */
    suspend fun sendFile(chat: ChatRef, file: java.io.File, name: String, mime: String, caption: String = ""): String

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

/** A button under a message: [data] comes back in the [Press] when it is tapped (at most 64 bytes). */
data class Button(val label: String, val data: String)

/** A tap on a [Button]: [id] to acknowledge it, [message] the message it was under. */
data class Press(val id: String, val data: String, val message: String)

/** A file attached to a message, still on the channel's servers: [id] is the channel's own; [size] -1 if unknown. */
data class RemoteFile(val id: String, val name: String, val mime: String, val size: Long)

/** Who sent a message: [id] is the channel's own user id, [name] what to show for them. */
data class Sender(val id: String, val name: String)

/**
 * A message to the assistant, or a tap on one of its buttons ([press], with empty [text]). [private]: a
 * one-to-one chat with the bot (groups are not supported yet). [files]: photos and documents with it (an
 * album comes as one message). [unsupported]: it carried something else (a voice note, a sticker), not handled yet.
 */
data class Inbound(
    val chat: ChatRef,
    val from: Sender,
    val text: String,
    val private: Boolean,
    val unsupported: Boolean = false,
    val press: Press? = null,
    val files: List<RemoteFile> = emptyList(),
)

/** A message in the channel's own markup ([markup]), and the same as plain text in case the markup is refused. */
data class Rich(val markup: String, val plain: String)

/**
 * The channel can't go on: [fatal] = it won't work until the user changes something (a revoked token,
 * another program reading the same bot); otherwise it is retried.
 */
class ChannelException(message: String, val fatal: Boolean = false) : Exception(message)
