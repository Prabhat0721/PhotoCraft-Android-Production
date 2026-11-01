package com.prabhat.photocraft.export

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream

object JpegMetadata {
    private const val SESSION_PREFIX = "PhotoCraftSession:1:"
    private const val ICC_PREFIX = "ICC_PROFILE\u0000"
    private const val SESSION_CHUNK_BYTES = 50_000
    private const val ICC_CHUNK_BYTES = 60_000

    fun write(
        input: InputStream,
        output: OutputStream,
        sessionPayload: String?,
        iccProfile: ByteArray?,
        isCancelled: () -> Boolean
    ): Boolean {
        val source = PushbackInputStream(BufferedInputStream(input), 4)
        val first = source.read()
        val second = source.read()
        if (first != 0xFF || second != 0xD8) {
            return false
        }

        output.write(0xFF)
        output.write(0xD8)
        copyLeadingJfif(source, output)
        if (iccProfile != null && iccProfile.isNotEmpty()) {
            writeIcc(output, iccProfile)
        }
        if (!sessionPayload.isNullOrBlank()) {
            writeSession(output, sessionPayload)
        }

        val buffer = ByteArray(64 * 1024)
        while (true) {
            if (isCancelled()) return false
            val count = source.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        output.flush()
        return true
    }

    fun readSession(input: InputStream): String? {
        return try {
            val source = BufferedInputStream(input)
            if (source.read() != 0xFF || source.read() != 0xD8) {
                return null
            }
            val chunks = LinkedHashMap<Int, String>()
            var expectedCount = 0
            while (true) {
                val marker = readMarker(source)
                if (marker < 0 || marker == 0xDA || marker == 0xD9) break
                if (marker == 0x01 || marker in 0xD0..0xD7) continue
                val length = readU16(source)
                if (length < 2) break
                val payload = ByteArray(length - 2)
                readFully(source, payload)
                if (marker != 0xFE) continue
                val text = String(payload, Charsets.US_ASCII)
                if (!text.startsWith(SESSION_PREFIX)) continue
                val rest = text.substring(SESSION_PREFIX.length)
                val firstSeparator = rest.indexOf(':')
                val secondSeparator = rest.indexOf(':', firstSeparator + 1)
                if (firstSeparator <= 0 || secondSeparator <= firstSeparator) continue
                val index = rest.substring(0, firstSeparator).toIntOrNull() ?: continue
                val count = rest.substring(firstSeparator + 1, secondSeparator)
                    .toIntOrNull() ?: continue
                if (count !in 1..64 || index !in 1..count) continue
                expectedCount = count
                chunks[index] = rest.substring(secondSeparator + 1)
            }
            if (expectedCount <= 0 || chunks.size != expectedCount) {
                return null
            }
            buildString {
                for (index in 1..expectedCount) {
                    append(chunks[index] ?: return null)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun copyLeadingJfif(input: PushbackInputStream, output: OutputStream) {
        while (true) {
            val first = input.read()
            if (first < 0) return
            if (first != 0xFF) {
                input.unread(first)
                return
            }
            val second = input.read()
            if (second < 0) {
                input.unread(first)
                return
            }
            if (second != 0xE0) {
                input.unread(second)
                input.unread(first)
                return
            }
            val length = readU16(input)
            if (length < 2) {
                input.unread(second)
                input.unread(first)
                return
            }
            val payload = ByteArray(length - 2)
            readFully(input, payload)
            output.write(0xFF)
            output.write(0xE0)
            output.write((length ushr 8) and 0xFF)
            output.write(length and 0xFF)
            output.write(payload)
        }
    }

    private fun writeSession(output: OutputStream, payload: String) {
        val bytes = payload.toByteArray(Charsets.US_ASCII)
        val count = (bytes.size + SESSION_CHUNK_BYTES - 1) / SESSION_CHUNK_BYTES
        for (index in 0 until count) {
            val start = index * SESSION_CHUNK_BYTES
            val end = minOf(bytes.size, start + SESSION_CHUNK_BYTES)
            val header = "$SESSION_PREFIX${index + 1}:$count:".toByteArray(Charsets.US_ASCII)
            val markerPayload = ByteArray(header.size + (end - start))
            System.arraycopy(header, 0, markerPayload, 0, header.size)
            System.arraycopy(bytes, start, markerPayload, header.size, end - start)
            writeMarker(output, 0xFE, markerPayload)
        }
    }

    private fun writeIcc(output: OutputStream, profile: ByteArray) {
        val count = (profile.size + ICC_CHUNK_BYTES - 1) / ICC_CHUNK_BYTES
        for (index in 0 until count) {
            val start = index * ICC_CHUNK_BYTES
            val end = minOf(profile.size, start + ICC_CHUNK_BYTES)
            val header = ICC_PREFIX.toByteArray(Charsets.US_ASCII)
            val markerPayload = ByteArray(header.size + 2 + (end - start))
            System.arraycopy(header, 0, markerPayload, 0, header.size)
            markerPayload[header.size] = (index + 1).toByte()
            markerPayload[header.size + 1] = count.toByte()
            System.arraycopy(profile, start, markerPayload, header.size + 2, end - start)
            writeMarker(output, 0xE2, markerPayload)
        }
    }

    private fun writeMarker(output: OutputStream, marker: Int, payload: ByteArray) {
        val length = payload.size + 2
        require(length <= 0xFFFF) { "JPEG marker payload is too large." }
        output.write(0xFF)
        output.write(marker)
        output.write((length ushr 8) and 0xFF)
        output.write(length and 0xFF)
        output.write(payload)
    }

    private fun readMarker(input: InputStream): Int {
        var value = input.read()
        while (value != 0xFF) {
            if (value < 0) return -1
            value = input.read()
        }
        var marker = input.read()
        while (marker == 0xFF) {
            marker = input.read()
        }
        return marker
    }

    private fun readU16(input: InputStream): Int {
        val high = input.read()
        val low = input.read()
        if (high < 0 || low < 0) return -1
        return (high shl 8) or low
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) throw IOException("Unexpected end of JPEG metadata.")
            offset += count
        }
    }
}
