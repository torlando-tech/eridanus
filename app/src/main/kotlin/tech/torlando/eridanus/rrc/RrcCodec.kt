// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rrc

import co.nstant.`in`.cbor.CborBuilder
import co.nstant.`in`.cbor.CborDecoder
import co.nstant.`in`.cbor.CborEncoder
import co.nstant.`in`.cbor.model.ByteString
import co.nstant.`in`.cbor.model.DataItem
import co.nstant.`in`.cbor.model.MajorType
import co.nstant.`in`.cbor.model.Map as CborMap
import co.nstant.`in`.cbor.model.NegativeInteger
import co.nstant.`in`.cbor.model.SimpleValue
import co.nstant.`in`.cbor.model.UnicodeString
import co.nstant.`in`.cbor.model.UnsignedInteger
import co.nstant.`in`.cbor.model.Array as CborArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger

object RrcCodec {

    fun encode(envelope: kotlin.collections.Map<Int, Any?>): ByteArray {
        val baos = ByteArrayOutputStream()
        val encoder = CborEncoder(baos)
        val map = CborMap()
        for ((key, value) in envelope) {
            map.put(UnsignedInteger(key.toLong()), toDataItem(value))
        }
        encoder.encode(map)
        return baos.toByteArray()
    }

    fun encodeStringKeyed(data: kotlin.collections.Map<String, Any?>): ByteArray {
        val baos = ByteArrayOutputStream()
        val encoder = CborEncoder(baos)
        val map = CborMap()
        for ((key, value) in data) {
            map.put(UnicodeString(key), toDataItem(value))
        }
        encoder.encode(map)
        return baos.toByteArray()
    }

    fun decode(data: ByteArray): kotlin.collections.Map<Int, Any?> {
        val bais = ByteArrayInputStream(data)
        val items = CborDecoder(bais).decode()
        if (items.isEmpty()) throw IllegalArgumentException("Empty CBOR data")
        val map = items[0] as? CborMap ?: throw IllegalArgumentException("Expected CBOR map")
        return fromCborMap(map)
    }

    private fun toDataItem(value: Any?): DataItem {
        return when (value) {
            null -> SimpleValue.NULL
            is Int -> UnsignedInteger(value.toLong())
            is Long -> if (value >= 0) UnsignedInteger(value) else NegativeInteger(value)
            is String -> UnicodeString(value)
            is ByteArray -> ByteString(value)
            is Boolean -> if (value) SimpleValue.TRUE else SimpleValue.FALSE
            is kotlin.collections.Map<*, *> -> {
                val map = CborMap()
                for ((k, v) in value) {
                    val key = when (k) {
                        is Int -> UnsignedInteger(k.toLong())
                        is String -> UnicodeString(k)
                        else -> throw IllegalArgumentException("Unsupported map key type: ${k?.javaClass}")
                    }
                    map.put(key, toDataItem(v))
                }
                map
            }
            is List<*> -> {
                val arr = CborArray()
                for (item in value) {
                    arr.add(toDataItem(item))
                }
                arr
            }
            else -> throw IllegalArgumentException("Unsupported type: ${value.javaClass}")
        }
    }

    private fun fromCborMap(map: CborMap): kotlin.collections.Map<Int, Any?> {
        val result = mutableMapOf<Int, Any?>()
        for (key in map.keys) {
            val intKey = when (key.majorType) {
                MajorType.UNSIGNED_INTEGER -> (key as UnsignedInteger).value.toInt()
                else -> continue
            }
            result[intKey] = fromDataItem(map.get(key))
        }
        return result
    }

    private fun fromDataItem(item: DataItem): Any? {
        return when (item.majorType) {
            MajorType.UNSIGNED_INTEGER -> {
                val value = (item as UnsignedInteger).value
                if (value <= BigInteger.valueOf(Int.MAX_VALUE.toLong())) value.toInt()
                else value.toLong()
            }
            MajorType.NEGATIVE_INTEGER -> {
                val value = (item as NegativeInteger).value
                if (value >= BigInteger.valueOf(Int.MIN_VALUE.toLong())) value.toInt()
                else value.toLong()
            }
            MajorType.BYTE_STRING -> (item as ByteString).bytes
            MajorType.UNICODE_STRING -> (item as UnicodeString).string
            MajorType.ARRAY -> {
                val arr = item as CborArray
                arr.dataItems.map { fromDataItem(it) }
            }
            MajorType.MAP -> {
                val map = item as CborMap
                val result = mutableMapOf<Any?, Any?>()
                for (key in map.keys) {
                    result[fromDataItem(key)] = fromDataItem(map.get(key))
                }
                result
            }
            MajorType.SPECIAL -> {
                when (item) {
                    SimpleValue.TRUE -> true
                    SimpleValue.FALSE -> false
                    SimpleValue.NULL -> null
                    else -> null
                }
            }
            else -> null
        }
    }
}
