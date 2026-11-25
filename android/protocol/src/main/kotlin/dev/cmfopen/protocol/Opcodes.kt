package dev.cmfopen.protocol

/** A command-channel opcode: the (cmd1, cmd2) pair in every frame header. */
data class Op(val cmd1: Int, val cmd2: Int) {
    override fun toString() = "%04X:%04X".format(cmd1, cmd2)
}

/** Opcode table, mirroring src/cmfkit/protocol/opcodes.py and web/src/lib/protocol/opcodes.ts. */
object Ops {
    val TIME = Op(0xFFFF, 0x8004)
    val FIRMWARE_VERSION_GET = Op(0xFFFF, 0x8006)
    val FIRMWARE_VERSION_RET = Op(0xFFFF, 0x0006)
    val SERIAL_NUMBER_GET = Op(0x00DE, 0x0002)
    val SERIAL_NUMBER_RET = Op(0x00DE, 0x0001)
    val BATTERY = Op(0x005C, 0x0001)

    val AUTH_PAIR_REQUEST = Op(0xFFFF, 0x8047)
    val AUTH_PAIR_REPLY = Op(0xFFFF, 0x0048)
    val AUTH_PHONE_NAME = Op(0xFFFF, 0x8049)
    val AUTH_WATCH_MAC = Op(0xFFFF, 0x0049)
    val AUTH_NONCE_REQUEST = Op(0xFFFF, 0x804B)
    val AUTH_NONCE_REPLY = Op(0xFFFF, 0x004C)
    val AUTH_CONFIRM_REQUEST = Op(0xFFFF, 0x804D)
    val AUTH_CONFIRM_REPLY = Op(0xFFFF, 0x0004)
    val AUTH_FAILED = Op(0xFFFF, 0xA061)

    val APP_NOTIFICATION = Op(0x0065, 0x0001)
    /** Sent by the watch after each notification with an EMPTY body, even though it is not a plaintext op. */
    val APP_NOTIFICATION_ACK = Op(0x0065, 0x0003)
    val FIND_PHONE = Op(0x005B, 0x0001)
    val FIND_WATCH = Op(0x005D, 0x0001)
    val FIND_WATCH_ACK = Op(0x005D, 0x0003)

    val MUSIC_INFO_SET = Op(0xFFFF, 0x905C)
    val MUSIC_INFO_ACK = Op(0xFFFF, 0xA05C)
    val MUSIC_BUTTON = Op(0xFFFF, 0xA05D)

    val CONTACTS_SET = Op(0x00D5, 0x0001)
    val CONTACTS_ACK = Op(0x00D5, 0x0003)
    val WEATHER_SET = Op(0xFFFF, 0x906B)
    val UNIT_LENGTH = Op(0xFFFF, 0x9067)
    val UNIT_TEMPERATURE = Op(0xFFFF, 0x9068)

    val WF_INIT1_REQUEST = Op(0xFFFF, 0x8052)
    val WF_INIT1_REPLY = Op(0xFFFF, 0x0052)
    val WF_INIT2_PHOTO_REQUEST = Op(0xFFFF, 0x9063)
    val WF_INIT2_PHOTO_REPLY = Op(0xFFFF, 0xA063)
    val WF_INIT2_STRUCTURED_REQUEST = Op(0xFFFF, 0x9075)
    val WF_INIT2_STRUCTURED_REPLY = Op(0xFFFF, 0xA075)
    val WF_CHUNK_REQUEST = Op(0xFFFF, 0xA064)
    val WF_CHUNK_WRITE = Op(0xFFFF, 0x9064)
    val WF_FINISH_REQUEST = Op(0xFFFF, 0xA065)
    val WF_FINISH_ACK = Op(0xFFFF, 0x9065)

    val DIAL_COMMAND = Op(0xFFFF, 0x9055)
    val DIAL_COMMAND_REPLY = Op(0xFFFF, 0xA055)

    val FW_CHUNK_WRITE = Op(0xFFFF, 0x9042)

    // Seen on connect with firmware 1.0.1.77 but not yet understood. Tolerated, logged.
    val UNKNOWN_LANGUAGE = Op(0xFFFF, 0xA06B)
    val UNKNOWN_A06A = Op(0xFFFF, 0xA06A)

    /** Opcodes that are never AES-encrypted, even after a key is established. */
    val PLAINTEXT: Set<Op> = setOf(AUTH_PAIR_REQUEST, AUTH_PAIR_REPLY, WF_CHUNK_WRITE, FW_CHUNK_WRITE, Op(0xFFFF, 0x905F))

    private val names: Map<Op, String> by lazy {
        Ops::class.java.declaredFields
            .filter { it.type == Op::class.java }
            .associate { f -> f.isAccessible = true; (f.get(this) as Op) to f.name }
    }

    fun nameOf(op: Op): String = names[op] ?: op.toString()
}
