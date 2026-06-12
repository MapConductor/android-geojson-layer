package com.mapconductor.geojson

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32
import java.util.zip.Inflater

class FastPngEncoderTest {
    @Test
    fun encodeWritesValidRgbaPng() {
        val rgba = byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(),
            0, 255.toByte(), 0, 255.toByte(),
            0, 0, 255.toByte(), 255.toByte(),
            255.toByte(), 255.toByte(), 255.toByte(), 0,
        )

        val png = FastPngEncoder.encode(2, 2, rgba)

        assertArrayEquals(
            byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10),
            png.copyOfRange(0, 8),
        )

        val chunks = readChunks(png)
        assertEquals(listOf("IHDR", "IDAT", "IEND"), chunks.map { it.type })

        val ihdr = chunks[0].data
        assertEquals(2, ihdr.readInt(0))
        assertEquals(2, ihdr.readInt(4))
        assertEquals(8, ihdr[8].toInt())
        assertEquals(6, ihdr[9].toInt())

        val inflated = inflate(chunks[1].data, expectedSize = 18)
        assertEquals(0, inflated[0].toInt())
        assertArrayEquals(rgba.copyOfRange(0, 8), inflated.copyOfRange(1, 9))
        assertEquals(0, inflated[9].toInt())
        assertArrayEquals(rgba.copyOfRange(8, 16), inflated.copyOfRange(10, 18))
    }

    private fun readChunks(png: ByteArray): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var offset = 8
        while (offset < png.size) {
            val length = png.readInt(offset)
            offset += 4
            val typeBytes = png.copyOfRange(offset, offset + 4)
            val type = typeBytes.decodeToString()
            offset += 4
            val data = png.copyOfRange(offset, offset + length)
            offset += length
            val expectedCrc = png.readInt(offset)
            offset += 4

            val crc = CRC32()
            crc.update(typeBytes)
            crc.update(data)
            assertEquals("CRC for $type", expectedCrc, crc.value.toInt())

            chunks += Chunk(type, data)
        }
        assertTrue(chunks.last().type == "IEND")
        return chunks
    }

    private fun inflate(data: ByteArray, expectedSize: Int): ByteArray {
        val inflater = Inflater()
        val output = ByteArray(expectedSize)
        try {
            inflater.setInput(data)
            val count = inflater.inflate(output)
            assertEquals(expectedSize, count)
            assertTrue(inflater.finished())
            return output
        } finally {
            inflater.end()
        }
    }

    private fun ByteArray.readInt(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)

    private data class Chunk(val type: String, val data: ByteArray)
}
