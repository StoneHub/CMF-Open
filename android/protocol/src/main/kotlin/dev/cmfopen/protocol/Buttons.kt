package dev.cmfopen.protocol

/**
 * Watch -> phone inputs. The music card's controls are the only buttons the stock firmware
 * sends; FIND_PHONE comes from the watch's "find phone" tile.
 *
 * The MUSIC_BUTTON code map is NOT confirmed on hardware (docs/02). These defaults match the
 * simulator; unknown codes surface as [Raw] so they can be logged and mapped on a real watch.
 */
sealed interface WatchButton {
    val id: String

    enum class Named(override val id: String) : WatchButton {
        PLAY("play"), PAUSE("pause"), NEXT("next"), PREV("prev"), VOL_UP("vol_up"), VOL_DOWN("vol_down"), FIND_PHONE("find_phone"),
    }

    data class Raw(val code: Int) : WatchButton {
        override val id = "raw:%02x".format(code)
    }

    companion object {
        val DEFAULT_CODES: Map<Int, Named> = mapOf(
            1 to Named.PLAY, 2 to Named.PAUSE, 3 to Named.NEXT, 4 to Named.PREV, 5 to Named.VOL_UP, 6 to Named.VOL_DOWN,
        )

        /** Map an unsolicited watch message to a button, or null if it isn't one. */
        fun from(msg: Message, codes: Map<Int, Named> = DEFAULT_CODES): WatchButton? = when {
            msg.op == Ops.MUSIC_BUTTON && msg.payload.isNotEmpty() -> msg.payload.u8(0).let { codes[it] ?: Raw(it) }
            msg.op == Ops.FIND_PHONE -> Named.FIND_PHONE
            else -> null
        }
    }
}
