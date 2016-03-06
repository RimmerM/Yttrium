package com.rimmer.yttrium.serialize

import com.rimmer.yttrium.*
import io.netty.buffer.ByteBuf
import org.joda.time.DateTime
import java.util.*

/**
 * Represents a type that can be serialized.
 * Currently, writable types need to support json and binary formats.
 */
interface Writable {
    /** Encodes the value as json and stores it in the provided buffer. */
    fun encodeJson(buffer: ByteBuf)

    /** Encodes the value as binary data and stores it in the provided buffer. */
    fun encodeBinary(buffer: ByteBuf)
}

/**
 * Stores a value as json.
 * The value must be either a Writable type, or any of the following builtin types:
 * Boolean, Byte, Short, Int, Long, Float, Double, DateTime, Char, String, Enum.
 */
fun writeJson(value: Any?, target: ByteBuf) {
    if(value is Writable) {
        value.encodeJson(target)
    } else {
        val writer = JsonWriter(target)
        when(value) {
            is Int -> writer.value(value)
            is Long -> writer.value(value)
            is String -> writer.value(value)
            is DateTime -> writer.value(value)
            is Enum<*> -> writer.value(value.name)
            is Boolean -> writer.value(value)
            is Float -> writer.value(value)
            is Double -> writer.value(value)
            is Char -> writer.value(value.toString())
            is Byte -> writer.value(value)
            is Short -> writer.value(value)
            else -> throw InvalidStateException("Value $value cannot be serialized.")
        }
    }
}

/**
 * Stores a value as binary data.
 * The value must be either a Writable type, or any of the following builtin types:
 * Boolean, Byte, Short, Int, Long, Float, Double, DateTime, Char, String.
 */
fun writeBinary(value: Any?, target: ByteBuf) {
    if(value is Writable) {
        value.encodeBinary(target)
    } else {
        val writer = BinaryWriter(target)
        when(value) {
            is Int -> writer.writeVarInt(value)
            is Long -> writer.writeVarLong(value)
            is String -> writer.writeString(value)
            is DateTime -> writer.writeVarLong(value.millis)
            is Enum<*> -> writer.writeVarInt(value.ordinal)
            is Boolean -> writer.writeVarInt(if(value) 1 else 0)
            is Float -> writer.writeFloat(value)
            is Double -> writer.writeDouble(value)
            is Char -> writer.writeString(value.toString())
            is Byte -> writer.writeVarInt(value.toInt())
            is Short -> writer.writeVarInt(value.toInt())
            else -> throw InvalidStateException("Value $value cannot be serialized.")
        }
    }
}

/**
 * Reads a primitive value from a string.
 * This can be used for parsing immediate parameters such as query strings.
 * Supported target types are:
 * Boolean, Byte, Short, Int, Long, Float, Double, DateTime, Char, String.
 */
fun readPrimitive(source: String, target: Class<*>): Any {
    if(target == Int::class.java) {
        return maybeParseInt(source) ?: throw InvalidStateException("Expected an integer")
    } else if(target == Long::class.java) {
        return maybeParseLong(source) ?: throw InvalidStateException("Expected an integer")
    } else if(target == String::class.java) {
        return source
    } else if(target == DateTime::class.java) {
        return DateTime.parse(source)
    } else if(target.isEnum) {
        return (target as Class<Enum<*>>).enumConstants.find {
            it.name == source
        } ?: throw InvalidStateException("Expected instance of enum $target")
    } else if(target == Boolean::class.java) {
        if(source == "true") return true
        else if(source == "false") return false
        else throw InvalidStateException("Expected a boolean")
    } else if(target == Float::class.java) {
        try {
            return java.lang.Float.parseFloat(source)
        } catch(e: Exception) {
            throw InvalidStateException("Expected a floating point value")
        }
    } else if(target == Double::class.java) {
        try {
            return java.lang.Double.parseDouble(source)
        } catch(e: Exception) {
            throw InvalidStateException("Expected a floating point value")
        }
    } else if(target == Char::class.java) {
        if(source.length == 1) {
            return source[0]
        } else {
            throw InvalidStateException("Expected a character")
        }
    } else if(target == Byte::class.java) {
        return maybeParseInt(source)?.toByte() ?: throw InvalidStateException("Expected an integer")
    } else if(target == Short::class.java) {
        return maybeParseInt(source)?.toShort() ?: throw InvalidStateException("Expected an integer")
    } else {
        throw InvalidStateException("Target type $target is not a primitive type.")
    }
}

/**
 * Reads a value from json.
 * The value must either be registered as a Readable type, or any of the following builtin types:
 * Boolean, Byte, Short, Int, Long, Float, Double, DateTime, Char, String.
 */
fun readJson(buffer: ByteBuf, target: Class<*>): Any {
    val readable = readableTypes[target]
    if(readable != null) {
        return readable.fromJson(buffer)
    } else {
        val reader = JsonToken(buffer)
        if(target == Int::class.java) {
            reader.expect(JsonToken.Type.NumberLit)
            return reader.numberPayload.toInt()
        } else if(target == Long::class.java) {
            reader.expect(JsonToken.Type.NumberLit)
            return reader.numberPayload.toLong()
        } else if(target == String::class.java) {
            reader.expect(JsonToken.Type.StringLit)
            return reader.stringPayload
        } else if(target == DateTime::class.java) {
            reader.parse()
            if(reader.type == JsonToken.Type.NumberLit) {
                return DateTime(reader.numberPayload.toLong())
            } else if(reader.type == JsonToken.Type.StringLit) {
                return DateTime.parse(reader.stringPayload)
            } else {
                throw InvalidStateException("Expected a json date.")
            }
        } else if(target.isEnum) {
            reader.expect(JsonToken.Type.StringLit)
            return (target as Class<Enum<*>>).enumConstants.find {
                it.name == reader.stringPayload
            } ?: throw InvalidStateException("Expected instance of enum $target")
        } else if(target == Boolean::class.java) {
            reader.expect(JsonToken.Type.BoolLit)
            return reader.boolPayload
        } else if(target == Float::class.java) {
            reader.expect(JsonToken.Type.NumberLit)
            return reader.numberPayload.toFloat()
        } else if(target == Double::class.java) {
            reader.expect(JsonToken.Type.NumberLit)
            return reader.numberPayload
        } else if(target == Char::class.java) {
            reader.expect(JsonToken.Type.StringLit)
            return reader.stringPayload.firstOrNull() ?: ' '
        } else if(target == Byte::class.java) {
            reader.expect(JsonToken.Type.NumberLit)
            return reader.numberPayload.toByte()
        } else if(target == Short::class.java) {
            reader.expect(JsonToken.Type.NumberLit)
            return reader.numberPayload.toShort()
        } else {
            throw InvalidStateException("Value cannot be parsed into $target.")
        }
    }
}

/**
 * Reads a value from binary data.
 * The value must either be registered as a Readable type, or any of the following builtin types:
 * Boolean, Byte, Short, Int, Long, Float, Double, DateTime, Char, String.
 */
fun readBinary(buffer: ByteBuf, target: Class<*>): Any {
    val readable = readableTypes[target]
    if(readable != null) {
        return readable.fromBinary(buffer)
    } else {
        val reader = BinaryReader(buffer)
        if(target == Int::class.java) {
            return reader.readVarInt()
        } else if(target == Long::class.java) {
            return reader.readVarLong()
        } else if(target == String::class.java) {
            return reader.readString()
        } else if(target == DateTime::class.java) {
            return DateTime(reader.readVarLong())
        } else if(target.isEnum) {
            val index = reader.readVarInt()
            val values = (target as Class<Enum<*>>).enumConstants
            if(values.size <= index || index < 0) {
                throw InvalidStateException("Expected instance of enum $target")
            }
            return values[index]
        } else if(target == Boolean::class.java) {
            return if(reader.readVarInt() == 0) false else true
        } else if(target == Float::class.java) {
            return reader.readFloat()
        } else if(target == Double::class.java) {
            return reader.readDouble()
        } else if(target == Char::class.java) {
            return reader.readString().firstOrNull() ?: ' '
        } else if(target == Byte::class.java) {
            return reader.readVarInt().toByte()
        } else if(target == Short::class.java) {
            return reader.readVarInt().toShort()
        } else {
            throw InvalidStateException("Value cannot be parsed into $target.")
        }
    }
}

class Readable(val fromJson: (ByteBuf) -> Any, val fromBinary: (ByteBuf) -> Any)

/** Registers a readable type. */
fun registerReadable(type: Class<*>, fromJson: (ByteBuf) -> Any, fromBinary: (ByteBuf) -> Any) {
    readableTypes[type] = Readable(fromJson, fromBinary)
}

/** Contains the currently registered readable types. */
private val readableTypes = HashMap<Class<*>, Readable>()