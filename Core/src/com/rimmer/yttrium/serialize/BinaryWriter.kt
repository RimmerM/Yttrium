package com.rimmer.yttrium.serialize

import com.rimmer.yttrium.ByteString
import io.netty.buffer.ByteBuf

/*
 * Helper functions for writing binary data.
 */

fun ByteBuf.writeByteString(s: ByteString) {
    writeVarInt(s.size)
    s.write(this)
}

fun ByteBuf.writeString(s: String) {
    val bytes = s.toByteArray(Charsets.UTF_8)
    writeVarInt(bytes.size)
    writeBytes(bytes)
}

fun ByteBuf.writeBinary(b: ByteBuf) {
    writeVarInt(b.readableBytes())
    writeBytes(b)
}

fun ByteBuf.writeBinary(b: ByteArray) {
    writeVarInt(b.size)
    writeBytes(b)
}

fun ByteBuf.writeVarInt(value: Int) {
    var v = value
    do {
        if(Integer.compareUnsigned(v, 0x7f) > 0) {
            writeByte(v or 0x80)
        } else {
            writeByte(v)
        }

        v = v ushr 7
    } while(v != 0)
}

fun ByteBuf.writeVarLong(value: Long) {
    var v = value

    do {
        if(java.lang.Long.compareUnsigned(v, 0x7f) > 0) {
            writeByte(v.toInt() or 0x80)
        } else {
            writeByte(v.toInt())
        }

        v = v ushr 7
    } while(v != 0L)
}

inline fun <T> ByteBuf.writeArray(valueType: Int, array: Collection<T>, write: ByteBuf.(T) -> Unit) {
    writeVarInt((array.size shl 3) or valueType)
    for(i in array) this.write(i)
}

inline fun <K, V> ByteBuf.writeMap(
    keyType: Int, valueType: Int, map: Map<K, V>,
    writeKey: ByteBuf.(K) -> Unit,
    writeValue: ByteBuf.(V) -> Unit
) {
    writeVarLong((map.size.toLong() shl 6) or keyType.toLong() or (valueType.toLong() shl 3))
    for(kv in map) {
        this.writeKey(kv.key)
        this.writeValue(kv.value)
    }
}