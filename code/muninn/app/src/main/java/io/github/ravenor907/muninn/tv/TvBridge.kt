package io.github.ravenor907.muninn.tv

/**
 * What the kids' TV needs from Chat beyond its public functions (sendPrivate, sendVoice, contacts,
 * conversations, unread, markRead, removeContact, deleteConversation). Chat implements it.
 */
interface TvBridge {
    /** Set by [Tv.install]. Chat asks it before taking an envelope and hands it room "tvconfig". */
    var tvHandler: TvHandler?

    /**
     * Seals [plain] for [peerKey] in room "tvconfig" (the ratchet when they have it, like private
     * messages) with a fresh envelope id and sends it once on every route. The TV code retries by itself
     * until the other side answers, so Chat keeps no outbox entry for it.
     */
    fun sendTvRoom(peerKey: String, plain: String)

    /** The same on every route at once, the internet included (so nobody nearby can stand in the way, 7.11). */
    fun sendTvRoomEverywhere(peerKey: String, plain: String) = sendTvRoom(peerKey, plain)
}

interface TvHandler {
    /**
     * Called for every envelope right after the blocked check, before it's opened. False: refuse it
     * (403). A device in kids' mode takes only its config, private messages, photos, voice, receipts, unsend
     * and calls only from the people its config lists, and group rooms only for groups a parent put it in; never
     * Nearby or Keep in touch.
     */
    fun allow(fromKey: String, room: String): Boolean

    /**
     * An opened envelope in room "tvconfig". [plain]: the whole plaintext (not cut to 2000 characters).
     * [mailbox]: the envelope's mailbox. [inviteOk]: the envelope carried a live invite from one of this
     * device's codes. True if it was taken (200), false to refuse it (403).
     */
    fun onTvRoom(fromKey: String, mailbox: String?, plain: String, inviteOk: Boolean): Boolean

    /** A group invite, or being put straight into a group (cap m1): a Kids' Device takes it only from one of its parents. */
    fun groupInviteOk(fromKey: String): Boolean = true
}
