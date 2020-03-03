package com.rimmer.yttrium.serialize

import com.rimmer.yttrium.ByteStringBuilder
import com.rimmer.yttrium.InvalidStateException
import com.rimmer.yttrium.emptyString
import com.rimmer.yttrium.utf8
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.util.*

/** Represents a json token with parsing functionality. */
class JsonToken(val buffer: ByteBuf, var useByteString: Boolean = false) {
    enum class Type {
        StartObject,
        EndObject,
        StartArray,
        EndArray,
        StringLit,
        NumberLit,
        BoolLit,
        NullLit,
        FieldName
    }

    var type = Type.StartObject
    var boolPayload = false
    var numberPayload = 0.0
    var stringPayload = ""
    var byteStringPayload = emptyString

    val bufferPayload: ByteArray get() = Base64.getDecoder().decode(stringPayload)

    fun expect(type: Type, allowNull: Boolean = false) {
        parse()

        if(this.type !== type) {
            if(this.type === Type.NullLit && allowNull) return

            // It is very common for js-produced values to contain strings that should be numbers.
            // We add a conversion here as a special case.
            if(type === Type.NumberLit && this.type === Type.StringLit) {
                try {
                    numberPayload = java.lang.Double.parseDouble(
                            if(useByteString) byteStringPayload.utf16() else stringPayload
                    )
                    this.type = type
                } catch(e: NumberFormatException) {
                    // If this happens the type will be wrong and we throw an exception later.
                }
            } else if(type === Type.StringLit && this.type === Type.NumberLit) {
                stringPayload = numberPayload.toString()
                if(useByteString) byteStringPayload = stringPayload.utf8
                this.type = type
            } else if(type === Type.BoolLit && this.type === Type.StringLit) {
                if(boolPayload) {
                    stringPayload = "true"
                    if(useByteString) byteStringPayload = stringPayload.utf8
                } else if(allowNull) {
                    this.type = Type.NullLit
                } else {
                    stringPayload = "false"
                    if(useByteString) byteStringPayload = stringPayload.utf8
                }
                this.type = type
            } else if(type === Type.BoolLit && this.type === Type.NumberLit) {
                numberPayload = if(boolPayload) 1.0 else 0.0
                this.type = type
            } else if(type === Type.NumberLit && this.type === Type.BoolLit) {
                boolPayload = numberPayload != 0.0
                this.type = type
            } else {
                throw InvalidStateException("Invalid json: Expected $type")
            }
        }
    }

    fun parse() {
        skipWhitespace()
        val b = buffer.readByte().toInt()
        when (b) {
            '['.toInt() -> type = Type.StartArray
            ']'.toInt() -> type = Type.EndArray
            '{'.toInt() -> type = Type.StartObject
            '}'.toInt() -> type = Type.EndObject
            else -> parseValue(b.toChar())
        }

        skipWhitespace()
        if(buffer.isReadable) {
            val c = buffer.getByte(buffer.readerIndex()).toChar()
            if (c == ',') {
                buffer.skipBytes(1)
            }
        }
    }

    fun skipValue() {
        parse()
        skipElement()
    }

    fun peekArrayEnd(): Boolean {
        skipWhitespace()
        return buffer.getByte(buffer.readerIndex()).toChar() == ']'
    }

    fun peekString(): Boolean {
        skipWhitespace()
        return buffer.getByte(buffer.readerIndex()).toChar() == '"'
    }

    fun peekNull(): Boolean {
        skipWhitespace()
        return (
            buffer.readableBytes() >= 4 &&
            buffer.getByte(buffer.readerIndex()).toChar() == 'n' &&
            buffer.getByte(buffer.readerIndex() + 1).toChar() == 'u' &&
            buffer.getByte(buffer.readerIndex() + 2).toChar() == 'l' &&
            buffer.getByte(buffer.readerIndex() + 3).toChar() == 'l'
        )
    }

    fun skipNull(): Boolean {
        if(peekNull()) {
            parse()
            return true
        }
        return false
    }

    private fun skipElement() {
        when {
            type === Type.StartObject -> skipObject()
            type === Type.StartArray -> skipArray()
            type === Type.EndObject || type === Type.EndArray ->
                throw InvalidStateException("Invalid json: Cannot have $type here.")
        }
    }

    private fun skipObject() {
        while(true) {
            parse()
            when {
                type === Type.EndObject -> return
                type === Type.FieldName -> skipValue()
                else -> throw InvalidStateException("Invalid json: Expected field name, got $type")
            }
        }
    }

    private fun skipArray() {
        while(true) {
            parse()
            if(type === Type.EndArray) return
            else skipElement()
        }
    }

    private fun parseValue(first: Char) {
        if(first == '"') {
            parseString()
            skipWhitespace()

            val f = if(buffer.isReadable) buffer.getByte(buffer.readerIndex()) else 0
            if(f.toChar() == ':') {
                type = Type.FieldName
                buffer.skipBytes(1)
            } else {
                type = Type.StringLit
            }
        } else if(isDigit(first) || first == '-' || first == '+') {
            type = Type.NumberLit
            numberPayload = parseFloat(first)
        } else if(first == 't') {
            expectChar('r')
            expectChar('u')
            expectChar('e')
            type = Type.BoolLit
            boolPayload = true
        } else if(first == 'f') {
            expectChar('a')
            expectChar('l')
            expectChar('s')
            expectChar('e')
            type = Type.BoolLit
            boolPayload = false
        } else if(first == 'n') {
            expectChar('u')
            expectChar('l')
            expectChar('l')
            type = Type.NullLit
        } else {
            throw InvalidStateException("Invalid json: expected a value, got '$first'")
        }
    }

    private fun parseFloat(first: Char): Double {
        var ch = first
        var out = 0.0

        // Check sign.
        var neg = false
        if(ch == '+') {
            ch = buffer.readByte().toChar()
        } else if(ch == '-') {
            ch = buffer.readByte().toChar()
            neg = true
        }

        // Create part before decimal point.
        while(isDigit(ch)) {
            val n = Character.digit(ch, 10)
            out *= 10.0
            out += n
            ch = buffer.readByte().toChar()
        }

        // Check if there is a fractional part.
        if(ch == '.') {
            ch = buffer.readByte().toChar()
            var dec = 0.0
            var dpl = 0

            while(isDigit(ch)) {
                val n = Character.digit(ch, 10)
                dec *= 10.0
                dec += n

                dpl++
                ch = buffer.readByte().toChar()
            }

            // We need to use a floating point power here in order to support more than 9 decimals.
            val power = Math.pow(10.0, dpl.toDouble())
            dec /= power
            out += dec
        }

        // Check if there is an exponent.
        if(ch == 'E' || ch == 'e') {
            ch = buffer.readByte().toChar()

            // Check sign.
            var signNegative = false
            if(ch == '+') {
                ch = buffer.readByte().toChar()
            } else if(ch == '-') {
                ch = buffer.readByte().toChar()
                signNegative = true
            }

            // Has exp. part;
            var exp = 0.0

            while(Character.isDigit(ch)) {
                val n = Character.digit(ch, 10)
                exp *= 10.0
                exp += n
                ch = buffer.readByte().toChar()
            }

            if(signNegative) exp = -exp

            val power = Math.pow(10.0, exp)
            out *= power
        }

        if(neg) out = -out

        // We read one character ahead, so restore it.
        buffer.readerIndex(buffer.readerIndex() - 1)
        return out
    }

    private fun parseString() {
        val string = ByteStringBuilder()
        while(true) {
            val b = buffer.readByte().toInt()
            if(b == '"'.toInt()) {
                break
            } else if(b == '\\'.toInt()) {
                parseEscaped(string)
            } else {
                string.append(b.toByte())
            }
        }

        if(useByteString) {
            byteStringPayload = string.string
        } else {
            stringPayload = String(string.buffer, 0, string.length, Charsets.UTF_8)
        }
    }

    private fun parseEscaped(string: ByteStringBuilder) {
        val b = buffer.readByte().toInt()
        when (b) {
            '"'.toInt() -> string.append('"'.toByte())
            '\\'.toInt() -> string.append('\\'.toByte())
            '/'.toInt() -> string.append('/'.toByte())
            'b'.toInt() -> string.append('\b'.toByte())
            'f'.toInt() -> string.append(0x0C.toByte())
            'n'.toInt() -> string.append('\n'.toByte())
            'r'.toInt() -> string.append('\r'.toByte())
            't'.toInt() -> string.append('\t'.toByte())
            'u'.toInt() -> parseUnicode(string)
        }
    }

    private fun parseUnicode(string: ByteStringBuilder) {
        val b0 = parseHexit(buffer.readByte())
        val b1 = parseHexit(buffer.readByte())
        val b2 = parseHexit(buffer.readByte())
        val b3 = parseHexit(buffer.readByte())
        val char = (b0 shl 12) or (b1 shl 8) or (b2 shl 4) or b3
        string.append("${char.toChar()}")
    }

    private fun skipWhitespace() {
        while(buffer.isReadable) {
            val b = buffer.getByte(buffer.readerIndex()).toInt()
            if(b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D) {
                buffer.skipBytes(1)
            } else {
                break
            }
        }
    }

    private fun expectChar(c: Char) {
        val ch = buffer.readByte().toChar()
        if(ch != c) {
            throw InvalidStateException("Invalid json: expected '$c'")
        }
    }
}

/**
 * Parses the provided character as a hexit, to an integer in the range 0..15.
 * @return The parsed number. Returns Nothing if the character is not a valid number.
 */
fun parseHexit(c: Byte): Int {
    val index = c - '0'.toByte()

    if(index < 0 || index > 54) {
        throw InvalidStateException("Invalid json: expected unicode sequence, got '$c'")
    }

    val res = parseHexitLookup[index]
    if(res > 15) {
        throw InvalidStateException("Invalid json: expected unicode sequence, got '$c'")
    }

    return res
}

// We use a small lookup table for parseHexit,
// since the number of branches would be ridiculous otherwise.
val parseHexitLookup = arrayOf(
    0,  1,  2,  3,  4,  5,  6,  7,  8,  9,	/* 0..9 */
    255,255,255,255,255,255,255,			/* :..@ */
    10, 11, 12, 13, 14, 15,					/* A..F */
    255,255,255,255,255,255,255,			/* G..` */
    255,255,255,255,255,255,255,
    255,255,255,255,255,255,
    255,255,255,255,255,255,
    10, 11, 12, 13, 14, 15					/* a..f */
)

/**
 * Returns true if this is a digit.
 */
fun isDigit(c: Char): Boolean {
    val index = c - '0'
    return index in 0..9
}
