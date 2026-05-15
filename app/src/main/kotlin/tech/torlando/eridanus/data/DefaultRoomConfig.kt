// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.data

import org.json.JSONArray
import org.json.JSONObject

data class DefaultRoomConfig(
    val name: String,
    val topic: String = "",
    val modes: String = "+nrt",
    val key: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("topic", topic)
        put("modes", modes)
        put("key", key)
    }

    companion object {
        fun fromJson(obj: JSONObject): DefaultRoomConfig = DefaultRoomConfig(
            name = obj.optString("name", ""),
            topic = obj.optString("topic", ""),
            modes = obj.optString("modes", "+nrt"),
            key = obj.optString("key", ""),
        )

        fun listToJson(rooms: List<DefaultRoomConfig>): String {
            val arr = JSONArray()
            for (room in rooms) arr.put(room.toJson())
            return arr.toString()
        }

        fun listFromJson(json: String): List<DefaultRoomConfig> {
            if (json.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
