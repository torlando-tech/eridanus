package tech.torlando.ara.rrc

object RrcConstants {
    const val RRC_VERSION = 1

    // Envelope keys
    const val K_V = 0
    const val K_T = 1
    const val K_ID = 2
    const val K_TS = 3
    const val K_SRC = 4
    const val K_ROOM = 5
    const val K_BODY = 6
    const val K_NICK = 7

    // Message types
    const val T_HELLO = 1
    const val T_WELCOME = 2

    const val T_JOIN = 10
    const val T_JOINED = 11
    const val T_PART = 12
    const val T_PARTED = 13

    const val T_MSG = 20
    const val T_NOTICE = 21

    const val T_PING = 30
    const val T_PONG = 31

    const val T_ERROR = 40

    // HELLO body keys
    const val B_HELLO_NAME = 0
    const val B_HELLO_VER = 1
    const val B_HELLO_CAPS = 2

    // WELCOME body keys
    const val B_WELCOME_HUB = 0
    const val B_WELCOME_VER = 1
    const val B_WELCOME_CAPS = 2
    const val B_WELCOME_LIMITS = 3

    // Hub limits map keys (within B_WELCOME_LIMITS)
    const val L_MAX_NICK_BYTES = 0
    const val L_MAX_ROOM_NAME_BYTES = 1
    const val L_MAX_MSG_BODY_BYTES = 2
    const val L_MAX_ROOMS_PER_SESSION = 3
    const val L_RATE_LIMIT_MSGS_PER_MINUTE = 4

    // Default hub limits
    const val DEFAULT_MAX_NICK_BYTES = 32
    const val DEFAULT_MAX_ROOM_NAME_BYTES = 64
    const val DEFAULT_MAX_MSG_BODY_BYTES = 350
    const val DEFAULT_MAX_ROOMS_PER_SESSION = 32
    const val DEFAULT_RATE_LIMIT_MSGS_PER_MINUTE = 240

    // Destination name for hubs
    const val DEST_NAME = "rrc.hub"
}
