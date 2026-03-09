package tech.fouronesoft.kvnoid.file

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.fouronesoft.kvnoid.file.util.TestDataProvider
import tech.fouronesoft.kvnoid.util.DataSerializationUtils
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.collections.forEachIndexed
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Tests data integrity for writes
 *
 * Targeting file version: 20.26.02.16
 */
class KVNFileReadWriterWriteTest {

  @Test
  fun test_companion_writeToDisk_throwOnNullKeyData() {
    val kvnFileData: KVNFileData = TestDataProvider.generateDecryptedKVNData(KVNFileReadWriter.WRITE_VERSION_STRING)
    val fakeOutputStream = BufferedOutputStream(BufferedOutputStream.nullOutputStream())

    kvnFileData.keyData = null
    assertThrows<RuntimeException>("Should throw on null keyData") {
      KVNFileReadWriter.writeToDisk(kvnFileData, fakeOutputStream)
    }
  }

  @Test
  fun test_companion_writeToDisk_testDataIntegrity() {
    val kvnFileData: KVNFileData = TestDataProvider.generateDecryptedKVNData(KVNFileReadWriter.WRITE_VERSION_STRING)

    // Capture output to a B[]OS
    val capturedOutputStream = ByteArrayOutputStream()
    KVNFileReadWriter.writeToDisk(
      kvnFileData,
      BufferedOutputStream(capturedOutputStream)
    )

    // Let's just check the entire file; stupid way to do this  -but it's not a lot of data anyway
    val outputReader = BufferedInputStream(ByteArrayInputStream(capturedOutputStream.toByteArray()))
    assertContentEquals(KVNFileReadWriter.KVNFILE_HEADER_MAGIC, outputReader.readNBytes(4))
    assertContentEquals(KVNFileReadWriter.WRITE_VERSION_BYTES, outputReader.readNBytes(4))

    fun assertHasNullPaddingHere() {
      assertContentEquals(ByteArray(KVNFileReadWriter.SIZE_BYTES_PADDING), outputReader.readNBytes(4))
    }

    // Check UUID
    val bytesUUIDMostSig = outputReader.readNBytes(8)
    val bytesUUIDLeastSig = outputReader.readNBytes(8)
    assertEquals(
      kvnFileData.metadata.uuid,
      UUID(
        DataSerializationUtils.byteArrayToLongLE(bytesUUIDMostSig),
        DataSerializationUtils.byteArrayToLongLE(bytesUUIDLeastSig)
      )
    )

    // Take a look at these longs (date created and modified)
    assertEquals(
      kvnFileData.metadata.dateCreated.toEpochMilliseconds(),
      DataSerializationUtils.byteArrayToLongLE(outputReader.readNBytes(Long.SIZE_BYTES))
    )
    assertEquals(
      kvnFileData.metadata.dateModified.toEpochMilliseconds(),
      DataSerializationUtils.byteArrayToLongLE(outputReader.readNBytes(Long.SIZE_BYTES))
    )

    // Reserved bytes should be null right now
    assertContentEquals(ByteArray(24), outputReader.readNBytes(24))

    // Lengths - integers
    val lenCategory = DataSerializationUtils.byteArrayToIntLE(outputReader.readNBytes(Int.SIZE_BYTES))
    val lenNametag = DataSerializationUtils.byteArrayToIntLE(outputReader.readNBytes(Int.SIZE_BYTES))
    val lenKeyData = DataSerializationUtils.byteArrayToIntLE(outputReader.readNBytes(Int.SIZE_BYTES))
    val lenEncryptedV = DataSerializationUtils.byteArrayToIntLE(outputReader.readNBytes(Int.SIZE_BYTES))
    // Testing data read from file with written length matches byte array in UTF-8
    assertEquals(
      kvnFileData.metadata.category.size,
      lenCategory
    )
    assertEquals(
      kvnFileData.metadata.nametag.size,
      lenNametag
    )
    assertEquals(
      kvnFileData.keyData!!.serializeToBytes().size,
      lenKeyData
    )
    val inputBytes = ByteArray(kvnFileData.decryptedV!!.getLength())
    kvnFileData.decryptedV!!.getProvider().use { provider ->
      provider.get().forEachIndexed { index, ch ->
        inputBytes[index] = ch.code.toByte()
      }
      assertEquals(
        kvnFileData.keyData!!.encrypt(inputBytes).size,
        lenEncryptedV
      )
    }

    // Trailing reserved bytes should also be null; also test null padding (4B)
    assertContentEquals(ByteArray(52), outputReader.readNBytes(52))
    assertContentEquals(
      kvnFileData.metadata.category,
      outputReader.readNBytes(lenCategory)
    )
    assertContentEquals(
      kvnFileData.metadata.nametag,
      outputReader.readNBytes(lenNametag)
    )
    // Skip CRC
    outputReader.skipNBytes(Long.SIZE_BYTES.toLong())
    assertHasNullPaddingHere()

    // Body content
    assertContentEquals(
      kvnFileData.keyData!!.serializeToBytes(),
      outputReader.readNBytes(lenKeyData)
    )
    assertContentEquals(
      kvnFileData.keyData!!.encrypt(inputBytes),
      outputReader.readNBytes(lenEncryptedV)
    )
    assertHasNullPaddingHere()
  }
}