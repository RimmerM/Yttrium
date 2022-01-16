package com.rimmer.yttrium.serialize

import com.rimmer.yttrium.*
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import java.util.*

/**
 * Represents a type that can be serialized.
 * Currently, writable types need to support json and binary formats.
 */
interface Writable {
    /** Encodes the value as json and stores it in the provided buffer. */
    fun encodeJson(writer: JsonWriter)

    /** Encodes the value as binary data and stores it in the provided buffer. */
    fun encodeBinary(buffer: ByteBuf)
}

class Writer<in T>(val toJson: JsonWriter.(T) -> Unit, val toBinary: ByteBuf.(T) -> Unit, val type: Int)
class Reader(val target: Class<*>, val fromJson: (JsonToken) -> Any, val fromBinary: (ByteBuf) -> Any)

// Writers for default types.
val intWriter = Writer<Int>({ value(it) }, { writeVarInt(it) }, FieldType.VarInt)
val longWriter = Writer<Long>({ value(it) }, { writeVarLong(it) }, FieldType.VarInt)
val byteStringWriter = Writer<ByteString>({ value(it) }, { writeByteString(it) }, FieldType.LengthEncoded)
val stringWriter = Writer<String>({ value(it) }, { writeString(it) }, FieldType.LengthEncoded)
val dateTimeWriter = Writer<DateTime>({ value(it) }, { writeVarLong(it.millis) }, FieldType.VarInt)
val dateWriter = Writer<Date>({ value(it.time) }, { writeVarLong(it.time) }, FieldType.VarInt)
val enumWriter = Writer<Enum<*>>({ value(it.name) }, { writeVarInt(it.ordinal) }, FieldType.VarInt)
val booleanWriter = Writer<Boolean>({ value(it) }, { writeBoolean(it) }, FieldType.VarInt)
val floatWriter = Writer<Float>({ value(it) }, { writeFloat(it) }, FieldType.Fixed32)
val doubleWriter = Writer<Double>({ value(it) }, { writeDouble(it) }, FieldType.Fixed64)
val charWriter = Writer<Char>({ value(it.toString()) }, { writeString(it.toString()) }, FieldType.LengthEncoded)
val byteWriter = Writer<Byte>({ value(it) }, { writeVarInt(it.toInt()) }, FieldType.VarInt)
val shortWriter = Writer<Short>({ value(it) }, { writeVarInt(it.toInt()) }, FieldType.VarInt)
val unitWriter = Writer<Unit>({ startObject().endObject() }, {}, FieldType.Object)
val binaryWriter = Writer<ByteBuf>({ value(it) }, { writeVarInt(it.readableBytes()); writeBytes(it) }, FieldType.LengthEncoded)

fun <T> arrayWriter(writer: Writer<T>?) = if(writer === null) {
    Writer<List<T>>({
        startArray()
        for(i in it) {
            arrayField()
            (i as Writable).encodeJson(this)
        }
        endArray()
    }, {
        writeArray(FieldType.Object, it) {
            (it as Writable).encodeBinary(this)
        }
    }, FieldType.Array)
} else {
    Writer<List<T>>({
        startArray()
        for(i in it) {
            arrayField()
            writer.toJson(this, i)
        }
        endArray()
    }, {
        writeArray(writer.type, it) {
            writer.toBinary(this, it)
        }
    }, FieldType.Array)
}

fun <V> mapWriter(writer: Writer<V>?) = if(writer == null) {
    Writer<Map<String, V>>({
        startObject()
        for((key, value) in it) {
            field(key)
            (value as Writable).encodeJson(this)
        }
        endObject()
    }, {
        writeMap(FieldType.LengthEncoded, FieldType.Object, it, {
            writeString(it)
        }, {
            (it as Writable).encodeBinary(this)
        })
    }, FieldType.Map)
} else {
    Writer<Map<String, V>>({
        startObject()
        for((key, value) in it) {
            field(key)
            writer.toJson(this, value)
        }
        endObject()
    }, {
        writeMap(FieldType.LengthEncoded, writer.type, it, {
            writeString(it)
        }, {
            writer.toBinary(this, it)
        })
    }, FieldType.Map)
}


// Readers for default types.
val intReader = Reader(Int::class.javaObjectType, {
    it.expect(JsonToken.Type.NumberLit)
    it.numberPayload.toInt()
}, {
    it.readVarInt()
})

val longReader = Reader(Long::class.javaObjectType, {
    it.expect(JsonToken.Type.NumberLit)
    it.numberPayload.toLong()
}, {
    it.readVarLong()
})

val shortReader = Reader(Short::class.javaObjectType, {
    it.expect(JsonToken.Type.NumberLit)
    it.numberPayload.toInt().toShort()
}, {
    it.readVarInt().toShort()
})

val byteReader = Reader(Byte::class.javaObjectType, {
    it.expect(JsonToken.Type.NumberLit)
    it.numberPayload.toInt().toByte()
}, {
    it.readVarInt().toByte()
})

val charReader = Reader(Char::class.javaObjectType, {
    it.expect(JsonToken.Type.StringLit)
    it.stringPayload.first()
}, {
    it.readString().first()
})

val unitReader = Reader(Unit::class.javaObjectType, {
    it.expect(JsonToken.Type.StartObject)
    it.expect(JsonToken.Type.EndObject)
}, {})

val floatReader = Reader(Float::class.javaObjectType, {
    it.expect(JsonToken.Type.NumberLit)
    it.numberPayload.toFloat()
}, {
    it.readFloat()
})

val doubleReader = Reader(Double::class.javaObjectType, {
    it.expect(JsonToken.Type.NumberLit)
    it.numberPayload
}, {
    it.readDouble()
})

val booleanReader = Reader(Boolean::class.javaObjectType, {
    it.expect(JsonToken.Type.BoolLit)
    it.boolPayload
}, {
    it.readBoolean()
})

val stringReader = Reader(String::class.java, {
    val byte = it.useByteString
    it.useByteString = false
    it.expect(JsonToken.Type.StringLit)
    it.useByteString = byte
    it.stringPayload
}, {
    it.readString()
})

val byteStringReader = Reader(ByteString::class.java, {
    val byte = it.useByteString
    it.useByteString = true
    it.expect(JsonToken.Type.StringLit)
    it.useByteString = byte
    it.byteStringPayload
}, {
    it.readByteString()
})

val dateTimeReader = Reader(DateTime::class.java, {
    it.parse()
    when(it.type) {
        JsonToken.Type.NumberLit -> DateTime(it.numberPayload.toLong(), DateTimeZone.UTC)
        JsonToken.Type.StringLit -> DateTime.parse(it.stringPayload)
        else -> throw InvalidStateException("Expected a json date.")
    }
}, {
    DateTime(it.readVarLong(), DateTimeZone.UTC)
})

val dateReader = Reader(Date::class.java, {
    it.parse()
    when(it.type) {
        JsonToken.Type.NumberLit -> Date(it.numberPayload.toLong())
        JsonToken.Type.StringLit -> Date(DateTime.parse(it.stringPayload).millis)
        else -> throw InvalidStateException("Expected a json date.")
    }
}, {
    Date(it.readVarLong())
})

val binaryReader = Reader(ByteBuf::class.java, {
    it.expect(JsonToken.Type.StringLit)
    it.bufferPayload
}, {
    val length = it.readVarInt()
    it.readBytes(length)
})

fun <T> arrayReader(element: Reader) = Reader(List::class.java, {
    val list = ArrayList<T>()
    it.expect(JsonToken.Type.StartArray)
    while(!it.peekArrayEnd()) {
        list.add(element.fromJson(it) as T)
    }
    it.expect(JsonToken.Type.EndArray)
    list
}, {
    val list = ArrayList<T>()
    val length = it.readVarLong() ushr 3
    var index = 0
    while(index < length) {
        list.add(element.fromBinary(it) as T)
        index++
    }
    list
})

fun <V> mapReader(element: Reader) = Reader(Map::class.java, {
    val map = HashMap<String, V>()
    val bytes = it.useByteString
    it.useByteString = false
    it.expect(JsonToken.Type.StartObject)
    while(true) {
        it.parse()
        if(it.type === JsonToken.Type.EndObject) {
            break
        } else if(it.type === JsonToken.Type.FieldName) {
            val key = it.stringPayload
            val value = element.fromJson(it) as V
            map[key] = value
        } else {
            throw InvalidStateException("Invalid json: expected field or object end")
        }
    }

    it.useByteString = bytes
    map
}, {
    val map = HashMap<String, V>()
    val length = it.readVarLong() ushr 6
    var index = 0
    while(index < length) {
        map[it.readString()] = element.fromBinary(it) as V
        index++
    }
    map
})

/**
 * This is a special wrapper around String,
 * which when stored will contain the raw string instead of being serialized to json.
 */
data class RawString(val value: String): Writable {
    override fun encodeJson(writer: JsonWriter) {
        writer.buffer.writeBytes(value.toByteArray())
    }

    override fun encodeBinary(buffer: ByteBuf) {
        buffer.writeString(value)
    }

    companion object {
        val reader = Reader(RawString::class.java, {
            val bytes = ByteArray(it.buffer.readableBytes())
            it.buffer.readBytes(bytes)
            RawString(String(bytes))
        }, {
            RawString(it.readString())
        })
    }
}

/**
 * This is a special wrapper around ByteBuf,
 * which represents a parameter that receives the body of a request.
 */
data class BodyContent(val value: ByteBuf) {
    companion object {
        val reader = Reader(BodyContent::class.java, {
            throw IllegalStateException("The request body cannot be parsed as a normal type.")
        }, {
            BodyContent(it)
        })
    }
}

fun writeJson(value: Any?, writer: Writer<*>?, target: ByteBuf) {
    val json = JsonWriter(target)
    when {
        writer == null -> when(value) {
            is Writable -> value.encodeJson(json)
            is Unit -> {
                json.startObject()
                json.endObject()
            }
            else -> throw IllegalStateException("Value $value is not serializable.")
        }
        value != null -> (writer as Writer<Any>).toJson(json, value)
        else -> json.nullValue()
    }
}

fun writeBinary(value: Any?, writer: Writer<*>?, target: ByteBuf) {
    if(writer == null) {
        if(value is Writable) value.encodeBinary(target)
        else if(value !is Unit) throw IllegalStateException("Value $value is not serializable.")
    } else if(value != null) {
        (writer as Writer<Any>).toBinary(target, value)
    }
}

/**
 * Reads a primitive value from a string.
 * This can be used for parsing immediate parameters such as query strings.
 * Supported target types are:
 * Boolean, Byte, Short, Int, Long, Float, Double, DateTime, Char, String.
 */
fun readPrimitive(source: String, target: Class<*>): Any {
    if(target == Int::class.javaObjectType || target == Int::class.javaPrimitiveType) {
        return maybeParseInt(source) ?: throw InvalidStateException("\"$source\" cannot be parsed as an integer")
    } else if(target == Long::class.javaObjectType || target == Long::class.javaPrimitiveType) {
        return maybeParseLong(source) ?: throw InvalidStateException("\"$source\" cannot be parsed as an integer")
    } else if(target == String::class.java) {
        return source
    } else if(target == ByteString::class.java) {
        return source.utf8
    } else if(target == Unit::class.javaObjectType || target == Unit::class.javaPrimitiveType) {
        return Unit
    } else if(target == DateTime::class.java) {
        return maybeParseLong(source)?.let { DateTime(it, DateTimeZone.UTC) } ?: DateTime.parse(source)
    } else if(target.isEnum) {
        return (target as Class<Enum<*>>).enumConstants.find {
            it.name == source
        } ?: throw InvalidStateException("\"$source\" is not an instance of enum $target")
    } else if(target == Boolean::class.javaObjectType || target == Boolean::class.javaPrimitiveType) {
        return when (source) {
            "true" -> true
            "false" -> false
            else -> throw InvalidStateException("\"$source\" is not a boolean")
        }
    } else if(target == Float::class.javaObjectType || target == Float::class.javaPrimitiveType) {
        try {
            return java.lang.Float.parseFloat(source)
        } catch(e: Exception) {
            throw InvalidStateException("\"$source\" cannot be parsed as a number")
        }
    } else if(target == Double::class.javaObjectType || target == Double::class.javaPrimitiveType) {
        try {
            return java.lang.Double.parseDouble(source)
        } catch(e: Exception) {
            throw InvalidStateException("\"$source\" cannot be parsed as a number")
        }
    } else if(target == Char::class.javaObjectType || target == Char::class.javaPrimitiveType) {
        if(source.length == 1) {
            return source[0]
        } else {
            throw InvalidStateException("\"$source\" is not a character")
        }
    } else if(target == Byte::class.javaObjectType || target == Byte::class.javaPrimitiveType) {
        return maybeParseInt(source)?.toByte() ?: throw InvalidStateException("\"$source\" cannot be parsed as an integer")
    } else if(target == Short::class.javaObjectType || target == Short::class.javaPrimitiveType) {
        return maybeParseInt(source)?.toShort() ?: throw InvalidStateException("\"$source\" cannot be parsed as an integer")
    } else {
        throw IllegalArgumentException("Target type $target is not a primitive type.")
    }
}

/** Helper function for parsing auto-generated data types. */
inline fun parseJsonObject(buffer: ByteBuf, field: (JsonToken) -> Boolean) {
    val token = JsonToken(buffer)
    token.expect(JsonToken.Type.StartObject)

    while(true) {
        token.parse()
        if(token.type == JsonToken.Type.EndObject) {
            break
        } else if(token.type == JsonToken.Type.FieldName) {
            if(!field(token)) {
                token.skipValue()
            }
        } else {
            throw InvalidStateException("Invalid json: expected field or object end")
        }
    }
}
