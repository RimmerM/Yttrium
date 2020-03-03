package com.rimmer.yttrium.serialize

import com.rimmer.yttrium.ByteString
import com.rimmer.yttrium.LocalByteString
import io.netty.buffer.ByteBuf

object FieldType {
    val Empty = 0
    val VarInt = 1
    val Fixed32 = 2
    val Fixed64 = 3
    val LengthEncoded = 4
    val Object = 5
    val Map = 6
    val Array = 7
}

/*
 * Helper functions for reading binary data.
 */

fun ByteBuf.readObjectIndex() = readVarInt()

fun ByteBuf.readString(): String {
    val length = readVarInt()
    val bytes = ByteArray(length)
    readBytes(bytes)
    return String(bytes)
}

fun ByteBuf.readByteString(): ByteString {
    val length = readVarInt()
    val bytes = ByteArray(length)
    readBytes(bytes)
    return LocalByteString(bytes)
}

fun ByteBuf.readBinary(): ByteArray {
    val length = readVarInt()
    val array = ByteArray(length)
    readBytes(array)
    return array
}

fun ByteBuf.readVarInt(): Int {
    var v = 0
    var c = 0
    while(c < 5) {
        val b = readUnsignedByte().toInt()
        v = v or ((b and 0x80.inv()) shl (7 * c))

        if(b and 0x80 == 0) break
        c++
    }
    return v
}

fun ByteBuf.readVarLong(): Long {
    var v = 0L
    var c = 0
    while(c < 10) {
        val b = readUnsignedByte().toLong()
        v = v or ((b and 0x80.inv()) shl (7 * c))

        if(b and 0x80 == 0L) break
        c++
    }
    return v
}

inline fun ByteBuf.mapObject(onField: (id: Int, type: Int) -> Unit) {
    var header = readVarInt()
    var count = 0
    var id = 0

    while(true) {
        val type = header and 0b111
        if(type != 0) onField(id, type)
        header = header ushr 3
        count++
        id++

        if(header == 0) {
            return
        } else if(count >= 10 && header != 0) {
            header = readVarInt()
            count = 0
        }
    }
}

inline fun ByteBuf.readObject(onField: (id: Int) -> Boolean) = mapObject { id, type ->
    if(!onField(id)) {
        skipField(type)
    }
}

inline fun ByteBuf.readMap(onPair: (index: Long, fromType: Int, toType: Int) -> Unit) {
    val header = readVarLong()
    val fromType = header.toInt() and 0b111
    val toType = (header.toInt() shr 3) and 0b111
    val length = header ushr 6
    for(i in 0..length - 1) {
        onPair(i, fromType, toType)
    }
}

inline fun ByteBuf.readArray(onValue: (index: Long, type: Int) -> Unit) {
    val header = readVarLong()
    val type = header.toInt() and 0b111
    val length = header shr 3
    for(i in 0 until length) {
        onValue(i, type)
    }
}

fun ByteBuf.skipField(type: Int) {
    when(type) {
        FieldType.Empty -> {}
        FieldType.VarInt -> readVarLong()
        FieldType.Fixed32 -> skipBytes(4)
        FieldType.Fixed64 -> skipBytes(8)
        FieldType.LengthEncoded -> {
            val length = readVarInt()
            skipBytes(length)
        }
        FieldType.Object -> mapObject { _, type -> skipField(type)}
        FieldType.Map -> readMap { _, from, to ->
            skipField(from)
            skipField(to)
        }
        FieldType.Array -> readArray { _, type ->
            skipField(type)
        }
    }
}
