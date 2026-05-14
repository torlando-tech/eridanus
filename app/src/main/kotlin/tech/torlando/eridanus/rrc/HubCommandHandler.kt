// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rrc

import tech.torlando.eridanus.rns.RnsLink

class HubCommandHandler(
    private val hub: RrcHub,
    private val roomManager: HubRoomManager,
) {

    /**
     * Handle a slash command from a session.
     * Returns true if the command was recognized (even if it errored).
     */
    fun handle(session: HubSession, room: String?, text: String): Boolean {
        val cmdline = text.trim()
        if (!cmdline.startsWith("/")) return false

        val parts = cmdline.substring(1).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false

        val cmd = parts[0].lowercase()
        val peerHash = session.peerHash ?: return false

        return when (cmd) {
            "list" -> handleList(session)
            "who", "names" -> handleWho(session, parts, room)
            "topic" -> handleTopic(session, parts, room, peerHash)
            "kick" -> handleKick(session, parts, room, peerHash)
            "ban" -> handleBan(session, parts, room, peerHash)
            "op" -> handleOpDeop(session, parts, room, peerHash, grant = true)
            "deop" -> handleOpDeop(session, parts, room, peerHash, grant = false)
            "voice" -> handleVoiceDevoice(session, parts, room, peerHash, grant = true)
            "devoice" -> handleVoiceDevoice(session, parts, room, peerHash, grant = false)
            "mode" -> handleMode(session, parts, room, peerHash)
            "register" -> handleRegister(session, parts, room, peerHash)
            "unregister" -> handleUnregister(session, parts, room, peerHash)
            "invite" -> handleInvite(session, parts, room, peerHash)
            "nick" -> handleNick(session, parts)
            "kline" -> handleKline(session, parts, peerHash)
            else -> false
        }
    }

    // ── /list ──────────────────────────────────────────────────────────

    private fun handleList(session: HubSession): Boolean {
        val registeredRooms = mutableListOf<Pair<String, String?>>()
        for ((name, st) in roomManager.allRoomStates) {
            if (st.registered && !st.isPrivate) {
                registeredRooms.add(name to st.topic)
            }
        }

        if (registeredRooms.isEmpty()) {
            hub.sendNotice(session.link, null, "No public rooms registered")
            return true
        }

        registeredRooms.sortBy { it.first }
        val lines = mutableListOf("Registered public rooms:")
        for ((name, topic) in registeredRooms) {
            if (topic != null) lines.add("  $name - $topic")
            else lines.add("  $name")
        }
        hub.sendNotice(session.link, null, lines.joinToString("\n"))
        return true
    }

    // ── /who ───────────────────────────────────────────────────────────

    private fun handleWho(session: HubSession, parts: List<String>, envelopeRoom: String?): Boolean {
        val targetRoom = if (parts.size >= 2) parts[1].trim().lowercase() else envelopeRoom
        if (targetRoom == null) {
            hub.sendNotice(session.link, null, "usage: /who [room]")
            return true
        }

        val st = roomManager.getState(targetRoom)
        if (st != null && st.isPrivate) {
            hub.sendNotice(session.link, null, "room $targetRoom is private")
            return true
        }

        val members = mutableListOf<String>()
        for (memberLink in roomManager.getMembers(targetRoom)) {
            val s = hub.getSession(memberLink) ?: continue
            val nick = s.nick
            val ph = s.peerHash
            val ident = ph?.joinToString("") { "%02x".format(it) } ?: "?"
            if (nick != null && nick.isNotEmpty()) {
                members.add("$nick (${ident.take(12)})")
            } else {
                members.add(ident)
            }
        }
        val memberStr = if (members.isNotEmpty()) members.joinToString(", ") else "(none)"
        hub.sendNotice(session.link, targetRoom, "members in $targetRoom: $memberStr")
        return true
    }

    // ── /topic ─────────────────────────────────────────────────────────

    private fun handleTopic(session: HubSession, parts: List<String>, envelopeRoom: String?, peerHash: ByteArray): Boolean {
        if (parts.size < 2) {
            hub.sendNotice(session.link, null, "usage: /topic <room> [topic]")
            return true
        }
        val r = parts[1].trim().lowercase()
        val st = roomManager.getOrCreateState(r)

        // View topic
        if (parts.size == 2) {
            val topic = st.topic
            hub.sendNotice(session.link, envelopeRoom, "topic for $r: ${topic ?: "(none)"}")
            return true
        }

        // Set topic — check +t
        if (st.topicOpsOnly && !roomManager.isOp(r, peerHash)) {
            hub.sendError(session.link, "not authorized (+t)", r)
            return true
        }

        val topic = parts.subList(2, parts.size).joinToString(" ").trim()
        st.topic = topic.ifEmpty { null }

        // Broadcast to all members
        val notice = if (topic.isNotEmpty()) "topic for $r is now: $topic" else "topic for $r is now: (cleared)"
        for (memberLink in roomManager.getMembers(r)) {
            hub.sendNotice(memberLink, r, notice)
        }
        return true
    }

    // ── /kick ──────────────────────────────────────────────────────────

    private fun handleKick(session: HubSession, parts: List<String>, envelopeRoom: String?, peerHash: ByteArray): Boolean {
        if (parts.size < 3) {
            hub.sendNotice(session.link, null, "usage: /kick <room> <nick|hashprefix>")
            return true
        }
        val r = parts[1].trim().lowercase()
        if (!roomManager.isOp(r, peerHash)) {
            hub.sendError(session.link, "not authorized", r)
            return true
        }

        val (targetLink, err) = resolveTarget(parts[2], r)
        if (targetLink == null) {
            hub.sendNotice(session.link, envelopeRoom, err)
            return true
        }

        val targetSession = hub.getSession(targetLink)
        if (targetSession == null || r !in targetSession.rooms) {
            hub.sendNotice(session.link, envelopeRoom, "target not in room")
            return true
        }

        // Remove target from room
        targetSession.rooms.remove(r)
        roomManager.removeMember(r, targetLink)

        hub.sendError(targetLink, "kicked from $r", r)
        hub.sendNotice(session.link, envelopeRoom, "kicked ${parts[2]} from $r")
        return true
    }

    // ── /ban ───────────────────────────────────────────────────────────

    private fun handleBan(session: HubSession, parts: List<String>, envelopeRoom: String?, peerHash: ByteArray): Boolean {
        if (parts.size < 3) {
            hub.sendNotice(session.link, null, "usage: /ban <room> add|del|list [nick|hashprefix]")
            return true
        }
        val r = parts[1].trim().lowercase()
        val op = parts[2].trim().lowercase()

        if (op == "list") {
            val st = roomManager.getState(r)
            val bans = st?.bans
            if (bans == null || bans.isEmpty()) {
                hub.sendNotice(session.link, envelopeRoom, "no bans in $r")
                return true
            }
            val items = bans.map { it.bytes.joinToString("") { b -> "%02x".format(b) } }.sorted()
            hub.sendNotice(session.link, envelopeRoom, "bans in $r: ${items.joinToString(", ")}")
            return true
        }

        if (op !in listOf("add", "del")) {
            hub.sendNotice(session.link, envelopeRoom, "usage: /ban <room> add|del|list [nick|hashprefix]")
            return true
        }

        if (parts.size < 4) {
            hub.sendNotice(session.link, envelopeRoom, "usage: /ban $r $op <nick|hashprefix>")
            return true
        }

        if (!roomManager.isOp(r, peerHash)) {
            hub.sendError(session.link, "not authorized", r)
            return true
        }

        val (targetLink, err) = resolveTarget(parts[3], r)
        if (targetLink == null) {
            hub.sendNotice(session.link, envelopeRoom, err)
            return true
        }

        val targetSession = hub.getSession(targetLink) ?: return true
        val targetHash = targetSession.peerHash ?: return true

        val st = roomManager.getOrCreateState(r)

        if (op == "add") {
            st.bans.add(targetHash.asKey())

            // Also kick target if present in room
            if (r in targetSession.rooms) {
                targetSession.rooms.remove(r)
                roomManager.removeMember(r, targetLink)
                hub.sendError(targetLink, "banned from $r", r)
            }

            hub.sendNotice(session.link, envelopeRoom, "ban added in $r")
        } else {
            st.bans.remove(targetHash.asKey())
            hub.sendNotice(session.link, envelopeRoom, "ban removed in $r")
        }
        return true
    }

    // ── /op, /deop ─────────────────────────────────────────────────────

    private fun handleOpDeop(session: HubSession, parts: List<String>, envelopeRoom: String?, peerHash: ByteArray, grant: Boolean): Boolean {
        val cmd = if (grant) "op" else "deop"
        if (parts.size < 3) {
            hub.sendNotice(session.link, null, "usage: /$cmd <room> <nick|hashprefix>")
            return true
        }
        val r = parts[1].trim().lowercase()
        if (!roomManager.isOp(r, peerHash)) {
            hub.sendError(session.link, "not authorized", r)
            return true
        }

        val (targetLink, err) = resolveTarget(parts[2], r)
        if (targetLink == null) {
            hub.sendNotice(session.link, envelopeRoom, err)
            return true
        }
        val targetSession = hub.getSession(targetLink) ?: return true
        val targetHash = targetSession.peerHash ?: return true

        val st = roomManager.getOrCreateState(r)
        if (grant) {
            st.ops.add(targetHash.asKey())
            hub.sendNotice(session.link, envelopeRoom, "op granted in $r")
        } else {
            val founder = st.founder
            if (founder != null && founder.contentEquals(targetHash)) {
                hub.sendNotice(session.link, envelopeRoom, "cannot deop founder")
                return true
            }
            st.ops.remove(targetHash.asKey())
            hub.sendNotice(session.link, envelopeRoom, "op removed in $r")
        }
        return true
    }

    // ── /voice, /devoice ───────────────────────────────────────────────

    private fun handleVoiceDevoice(session: HubSession, parts: List<String>, envelopeRoom: String?, peerHash: ByteArray, grant: Boolean): Boolean {
        val cmd = if (grant) "voice" else "devoice"
        if (parts.size < 3) {
            hub.sendNotice(session.link, null, "usage: /$cmd <room> <nick|hashprefix>")
            return true
        }
        val r = parts[1].trim().lowercase()
        if (!roomManager.isOp(r, peerHash)) {
            hub.sendError(session.link, "not authorized", r)
            return true
        }

        val (targetLink, err) = resolveTarget(parts[2], r)
        if (targetLink == null) {
            hub.sendNotice(session.link, envelopeRoom, err)
            return true
        }
        val targetSession = hub.getSession(targetLink) ?: return true
        val targetHash = targetSession.peerHash ?: return true

        val st = roomManager.getOrCreateState(r)
        if (grant) {
            st.voiced.add(targetHash.asKey())
            hub.sendNotice(session.link, envelopeRoom, "voice granted in $r")
        } else {
            st.voiced.remove(targetHash.asKey())
            hub.sendNotice(session.link, envelopeRoom, "voice removed in $r")
        }
        return true
    }

    // ── /mode ──────────────────────────────────────────────────────────

    private fun handleMode(session: HubSession, parts: List<String>, envelopeRoom: String?, peerHash: ByteArray): Boolean {
        if (parts.size < 3) {
            hub.sendNotice(session.link, null, "usage: /mode <room> (+m|-m|+i|-i|+t|-t|+n|-n|+p|-p|+k|-k) [key] | /mode <room> (+o|-o|+v|-v) <nick|hash>")
            return true
        }
        val r = parts[1].trim().lowercase()
        if (!roomManager.isOp(r, peerHash)) {
            hub.sendError(session.link, "not authorized", r)
            return true
        }

        val flag = parts[2].trim().lowercase()
        val st = roomManager.getOrCreateState(r)

        when (flag) {
            "+m" -> st.moderated = true
            "-m" -> st.moderated = false
            "+i" -> st.inviteOnly = true
            "-i" -> st.inviteOnly = false
            "+t" -> st.topicOpsOnly = true
            "-t" -> st.topicOpsOnly = false
            "+n" -> st.noOutsideMsgs = true
            "-n" -> st.noOutsideMsgs = false
            "+p" -> st.isPrivate = true
            "-p" -> st.isPrivate = false
            "+k" -> {
                if (parts.size < 4) {
                    hub.sendNotice(session.link, envelopeRoom, "usage: /mode <room> +k <key>")
                    return true
                }
                val key = parts.subList(3, parts.size).joinToString(" ").trim()
                if (key.isEmpty()) {
                    hub.sendNotice(session.link, envelopeRoom, "key must not be empty")
                    return true
                }
                st.key = key
            }
            "-k" -> st.key = null
            "+r", "-r" -> {
                hub.sendNotice(session.link, envelopeRoom, "use /register or /unregister to change +r")
                return true
            }
            "+o", "-o", "+v", "-v" -> {
                if (parts.size < 4) {
                    hub.sendNotice(session.link, envelopeRoom, "usage: /mode <room> $flag <nick|hash>")
                    return true
                }
                val (targetLink, err) = resolveTarget(parts[3], r)
                if (targetLink == null) {
                    hub.sendNotice(session.link, envelopeRoom, err)
                    return true
                }
                val targetSession = hub.getSession(targetLink) ?: return true
                val targetHash = targetSession.peerHash ?: return true

                when (flag) {
                    "+o" -> st.ops.add(targetHash.asKey())
                    "-o" -> {
                        val founder = st.founder
                        if (founder != null && founder.contentEquals(targetHash)) {
                            hub.sendNotice(session.link, envelopeRoom, "cannot deop founder")
                            return true
                        }
                        st.ops.remove(targetHash.asKey())
                    }
                    "+v" -> st.voiced.add(targetHash.asKey())
                    "-v" -> st.voiced.remove(targetHash.asKey())
                }

                val shortHash = targetHash.take(6).joinToString("") { "%02x".format(it) }
                for (memberLink in roomManager.getMembers(r)) {
                    hub.sendNotice(memberLink, r, "mode for $r is now: $flag $shortHash")
                }
                return true
            }
            else -> {
                hub.sendNotice(session.link, envelopeRoom, "supported modes: +m -m +i -i +k -k +t -t +n -n +p -p +r -r +o -o +v -v")
                return true
            }
        }

        // Broadcast mode change to all members
        val modeStr = roomManager.getModeString(r)
        for (memberLink in roomManager.getMembers(r)) {
            hub.sendNotice(memberLink, r, "mode for $r is now: $modeStr")
        }
        return true
    }

    // ── /register ──────────────────────────────────────────────────────

    private fun handleRegister(session: HubSession, parts: List<String>, envelopeRoom: String?, peerHash: ByteArray): Boolean {
        if (parts.size < 2) {
            hub.sendNotice(session.link, null, "usage: /register <room>")
            return true
        }
        val r = parts[1].trim().lowercase()

        // Must be in the room
        if (r !in session.rooms) {
            hub.sendNotice(session.link, envelopeRoom, "must be present in the room to register it")
            return true
        }

        val st = roomManager.getOrCreateState(r)
        val founder = st.founder
        if (founder == null || !founder.contentEquals(peerHash)) {
            hub.sendError(session.link, "only the room founder can register", r)
            return true
        }

        st.registered = true
        st.noOutsideMsgs = true
        st.topicOpsOnly = true
        hub.sendNotice(session.link, envelopeRoom, "registered room $r")
        return true
    }

    // ── /unregister ────────────────────────────────────────────────────

    private fun handleUnregister(session: HubSession, parts: List<String>, envelopeRoom: String?, peerHash: ByteArray): Boolean {
        if (parts.size < 2) {
            hub.sendNotice(session.link, null, "usage: /unregister <room>")
            return true
        }
        val r = parts[1].trim().lowercase()

        if (r !in session.rooms) {
            hub.sendNotice(session.link, envelopeRoom, "must be present in the room to unregister it")
            return true
        }

        val st = roomManager.getState(r)
        if (st == null) {
            hub.sendNotice(session.link, envelopeRoom, "room $r is not registered")
            return true
        }
        val founder = st.founder
        if (founder == null || !founder.contentEquals(peerHash)) {
            hub.sendError(session.link, "only the room founder can unregister", r)
            return true
        }
        if (!st.registered) {
            hub.sendNotice(session.link, envelopeRoom, "room $r is not registered")
            return true
        }

        st.registered = false
        hub.sendNotice(session.link, envelopeRoom, "unregistered room $r")
        return true
    }

    // ── /invite ────────────────────────────────────────────────────────

    private fun handleInvite(session: HubSession, parts: List<String>, envelopeRoom: String?, peerHash: ByteArray): Boolean {
        if (parts.size < 3) {
            hub.sendNotice(session.link, null, "usage: /invite <room> add|del|list [nick|hashprefix]")
            return true
        }
        val r = parts[1].trim().lowercase()

        if (!roomManager.isOp(r, peerHash)) {
            hub.sendError(session.link, "not authorized", r)
            return true
        }

        val op = parts[2].trim().lowercase()
        val st = roomManager.getOrCreateState(r)

        roomManager.pruneExpiredInvites(r)

        if (op == "list") {
            val now = System.currentTimeMillis()
            val items = mutableListOf<String>()
            for ((h, exp) in st.invited) {
                if (exp <= now) continue
                val hex = h.bytes.joinToString("") { "%02x".format(it) }
                items.add("$hex expires_in=${(exp - now) / 1000}s")
            }
            items.sort()
            val text = if (items.isNotEmpty()) items.joinToString(", ") else "(none)"
            hub.sendNotice(session.link, envelopeRoom, "invites in $r: $text")
            return true
        }

        if (op !in listOf("add", "del")) {
            hub.sendNotice(session.link, envelopeRoom, "usage: /invite <room> add|del|list [nick|hashprefix]")
            return true
        }

        if (parts.size < 4) {
            hub.sendNotice(session.link, envelopeRoom, "usage: /invite $r $op <nick|hashprefix>")
            return true
        }

        if (op == "add") {
            // Resolve target globally (not filtered to room)
            val (targetLink, err) = resolveTarget(parts[3], null)
            if (targetLink == null) {
                hub.sendError(session.link, "invite failed: $err", r)
                return true
            }
            val targetSession = hub.getSession(targetLink) ?: return true
            val targetHash = targetSession.peerHash ?: return true

            val isKeyed = !st.key.isNullOrEmpty()
            val isInviteOnly = st.inviteOnly

            if (isKeyed) {
                hub.sendNotice(targetLink, r, "You have been invited to join $r. This invite allows joining without the key (+k).")
            } else {
                hub.sendNotice(targetLink, r, "You have been invited to join $r.")
            }

            if (isKeyed || isInviteOnly) {
                val ttl = RrcConstants.DEFAULT_INVITE_TTL_MS
                st.invited[targetHash.asKey()] = System.currentTimeMillis() + ttl
                hub.sendNotice(session.link, envelopeRoom, "invite added in $r (expires in ${ttl / 1000}s)")
            } else {
                hub.sendNotice(session.link, envelopeRoom, "invite sent to ${parts[3]} for $r")
            }
            return true
        }

        // del
        val (targetLink, err) = resolveTarget(parts[3], null)
        if (targetLink == null) {
            hub.sendNotice(session.link, envelopeRoom, err)
            return true
        }
        val targetSession = hub.getSession(targetLink) ?: return true
        val targetHash = targetSession.peerHash ?: return true
        st.invited.remove(targetHash.asKey())
        hub.sendNotice(session.link, envelopeRoom, "invite removed in $r")
        return true
    }

    // ── /nick ──────────────────────────────────────────────────────────

    private fun handleNick(session: HubSession, parts: List<String>): Boolean {
        if (parts.size < 2) {
            hub.sendNotice(session.link, null, "usage: /nick <name>")
            return true
        }
        val newNick = parts.subList(1, parts.size).joinToString(" ").trim()
        if (newNick.isEmpty() || newNick.toByteArray().size > RrcConstants.DEFAULT_MAX_NICK_BYTES) {
            hub.sendError(session.link, "invalid nick")
            return true
        }

        val oldNick = session.nick
        session.nick = newNick
        hub.updateNickIndex(session.link, oldNick, newNick)
        hub.sendNotice(session.link, null, "nick changed to $newNick")
        return true
    }

    // ── /kline ─────────────────────────────────────────────────────────

    private fun handleKline(session: HubSession, parts: List<String>, peerHash: ByteArray): Boolean {
        if (!roomManager.isServerOp(peerHash)) {
            hub.sendError(session.link, "not authorized")
            return true
        }

        if (parts.size < 2) {
            hub.sendNotice(session.link, null, "usage: /kline add|del|list [nick|hashprefix|hash]")
            return true
        }

        val op = parts[1].trim().lowercase()

        if (op == "list") {
            val items = roomManager.globalBanList()
            val text = if (items.isNotEmpty()) items.joinToString(", ") else "(none)"
            hub.sendNotice(session.link, null, "klines: $text")
            return true
        }

        if (op !in listOf("add", "del")) {
            hub.sendNotice(session.link, null, "usage: /kline add|del|list [nick|hashprefix|hash]")
            return true
        }

        if (parts.size < 3) {
            hub.sendNotice(session.link, null, "usage: /kline $op <nick|hashprefix|hash>")
            return true
        }

        val target = parts[2]

        if (op == "add") {
            // Try to resolve as online user first
            val (targetLink, _) = resolveTarget(target, null)
            if (targetLink != null) {
                val targetSession = hub.getSession(targetLink)
                val targetHash = targetSession?.peerHash
                if (targetHash != null) {
                    roomManager.addGlobalBan(targetHash)
                    hub.sendError(targetLink, "banned from hub")
                    try { targetLink.teardown() } catch (_: Exception) {}
                    hub.sendNotice(session.link, null, "kline added for $target")
                    return true
                }
            }

            // Try as raw hex hash
            val hash = parseHexHash(target)
            if (hash == null) {
                hub.sendNotice(session.link, null, "target '$target' not found and not a valid hash")
                return true
            }
            roomManager.addGlobalBan(hash)
            // Disconnect if currently connected
            for ((link, s) in hub.allSessions()) {
                val ph = s.peerHash ?: continue
                if (ph.contentEquals(hash)) {
                    hub.sendError(link, "banned from hub")
                    try { link.teardown() } catch (_: Exception) {}
                }
            }
            hub.sendNotice(session.link, null, "kline added for ${hash.joinToString("") { "%02x".format(it) }}")
            return true
        }

        // del
        val hash = parseHexHash(target)
        if (hash == null) {
            hub.sendNotice(session.link, null, "bad identity hash: '$target'")
            return true
        }

        if (roomManager.isGloballyBanned(hash)) {
            roomManager.removeGlobalBan(hash)
            hub.sendNotice(session.link, null, "kline removed for ${hash.joinToString("") { "%02x".format(it) }}")
        } else {
            hub.sendNotice(session.link, null, "not klined: ${hash.joinToString("") { "%02x".format(it) }}")
        }
        return true
    }

    private fun parseHexHash(token: String): ByteArray? {
        val hex = token.trim().lowercase().removePrefix("0x").filter { !it.isWhitespace() }
        if (hex.length < 8 || !hex.all { it in "0123456789abcdef" }) return null
        return try {
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    // ── Target resolution ──────────────────────────────────────────────

    /**
     * Resolve a target by nick or hash prefix.
     * Returns (link, "") on success or (null, errorMessage) on failure.
     */
    private fun resolveTarget(token: String, room: String?): Pair<RnsLink?, String> {
        val t = token.trim().lowercase()
        if (t.isEmpty()) return null to "target '' not found"

        val hexCandidate = if (t.startsWith("0x")) t.substring(2) else t
        val isHex = hexCandidate.length >= 6 && hexCandidate.all { it in "0123456789abcdef" }

        if (isHex) {
            // Hash prefix search
            val matches = mutableListOf<RnsLink>()
            for ((link, s) in hub.allSessions()) {
                val ph = s.peerHash ?: continue
                val hex = ph.joinToString("") { "%02x".format(it) }
                if (hex.startsWith(hexCandidate)) {
                    if (room != null && room !in s.rooms) continue
                    matches.add(link)
                }
            }
            return when (matches.size) {
                1 -> matches[0] to ""
                0 -> null to "target '$token' not found"
                else -> null to "ambiguous: '$token' matches ${matches.size} identities"
            }
        }

        // Nick search (case-insensitive)
        val matches = mutableListOf<RnsLink>()
        for ((link, s) in hub.allSessions()) {
            if (s.nick?.lowercase() == t) {
                if (room != null && room !in s.rooms) continue
                matches.add(link)
            }
        }
        return when (matches.size) {
            1 -> matches[0] to ""
            0 -> null to "target '$token' not found"
            else -> null to "ambiguous: '$token' matches ${matches.size} identities"
        }
    }
}
