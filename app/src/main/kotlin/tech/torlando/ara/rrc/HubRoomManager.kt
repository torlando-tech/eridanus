package tech.torlando.ara.rrc

import network.reticulum.link.Link

class ByteArrayKey(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is ByteArrayKey && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = bytes.joinToString("") { "%02x".format(it) }
}

fun ByteArray.asKey() = ByteArrayKey(this)

data class HubRoomState(
    var founder: ByteArray? = null,
    var registered: Boolean = false,
    var topic: String? = null,
    var moderated: Boolean = false,
    var inviteOnly: Boolean = false,
    var topicOpsOnly: Boolean = false,
    var noOutsideMsgs: Boolean = false,
    var isPrivate: Boolean = false,
    var key: String? = null,
    val ops: MutableSet<ByteArrayKey> = mutableSetOf(),
    val voiced: MutableSet<ByteArrayKey> = mutableSetOf(),
    val bans: MutableSet<ByteArrayKey> = mutableSetOf(),
    val invited: MutableMap<ByteArrayKey, Long> = mutableMapOf(),
)

class HubRoomManager {

    private val roomState = mutableMapOf<String, HubRoomState>()
    private val roomMembers = mutableMapOf<String, MutableSet<Link>>()

    fun getOrCreateState(room: String, founder: ByteArray? = null): HubRoomState {
        val existing = roomState[room]
        if (existing != null) {
            if (existing.founder == null && founder != null) {
                existing.founder = founder
                existing.ops.add(founder.asKey())
            }
            return existing
        }
        val state = HubRoomState(
            founder = founder,
            ops = if (founder != null) mutableSetOf(founder.asKey()) else mutableSetOf(),
        )
        roomState[room] = state
        return state
    }

    fun getState(room: String): HubRoomState? = roomState[room]

    fun getMembers(room: String): Set<Link> = roomMembers[room] ?: emptySet()

    fun addMember(room: String, link: Link) {
        roomMembers.getOrPut(room) { mutableSetOf() }.add(link)
    }

    fun removeMember(room: String, link: Link) {
        roomMembers[room]?.remove(link)
        if (roomMembers[room]?.isEmpty() == true) {
            roomMembers.remove(room)
            val st = roomState[room]
            if (st != null && !st.registered) {
                roomState.remove(room)
            }
        }
    }

    fun removeFromAllRooms(link: Link): List<String> {
        val rooms = roomMembers.filter { link in it.value }.keys.toList()
        for (room in rooms) {
            removeMember(room, link)
        }
        return rooms
    }

    fun isOp(room: String, hash: ByteArray?): Boolean {
        if (hash == null) return false
        val st = roomState[room] ?: return false
        val founder = st.founder
        if (founder != null && founder.contentEquals(hash)) return true
        return hash.asKey() in st.ops
    }

    fun isVoiced(room: String, hash: ByteArray?): Boolean {
        if (hash == null) return false
        if (isOp(room, hash)) return true
        val st = roomState[room] ?: return false
        return hash.asKey() in st.voiced
    }

    fun isBanned(room: String, hash: ByteArray?): Boolean {
        if (hash == null) return false
        val st = roomState[room] ?: return false
        return hash.asKey() in st.bans
    }

    fun isInvited(room: String, hash: ByteArray?): Boolean {
        if (hash == null) return false
        val st = roomState[room] ?: return false
        val key = hash.asKey()
        val expiry = st.invited[key] ?: return false
        if (expiry <= System.currentTimeMillis()) {
            st.invited.remove(key)
            return false
        }
        return true
    }

    /**
     * Returns null if join is allowed, or an error string if rejected.
     * Check order: bans, invite_only, key.
     */
    fun checkJoinAllowed(room: String, hash: ByteArray, key: String?): String? {
        val st = roomState[room]
        if (st != null) {
            if (isBanned(room, hash)) return "banned from room"

            val isOpOrInvited = isOp(room, hash) || isInvited(room, hash)

            if (st.inviteOnly && !isOpOrInvited) {
                return "invite-only (+i)"
            }

            val roomKey = st.key
            if (roomKey != null && roomKey.isNotEmpty() && !isOpOrInvited) {
                if (key != roomKey) return "bad key (+k)"
            }
        }
        return null
    }

    fun getModeString(room: String): String {
        val st = roomState[room] ?: return "(none)"
        val flags = buildString {
            if (st.inviteOnly) append("i")
            if (st.key != null && st.key!!.isNotEmpty()) append("k")
            if (st.moderated) append("m")
            if (st.noOutsideMsgs) append("n")
            if (st.isPrivate) append("p")
            if (st.registered) append("r")
            if (st.topicOpsOnly) append("t")
        }
        return if (flags.isNotEmpty()) "+$flags" else "(none)"
    }

    fun pruneExpiredInvites(room: String) {
        val st = roomState[room] ?: return
        val now = System.currentTimeMillis()
        st.invited.entries.removeAll { it.value <= now }
    }

    /** All rooms in state (for /list) */
    val allRoomStates: Map<String, HubRoomState> get() = roomState

    fun clear() {
        roomState.clear()
        roomMembers.clear()
    }
}
