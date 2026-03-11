package tech.fouronesoft.kvnoid.file

import org.junit.jupiter.api.Test
import tech.fouronesoft.kvnoid.file.util.TestDataProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

const val VERSION_STRING_TESTED_HERE = "20260216"

class KVNFileReadWriterReadTest20260216 {

  companion object {
    fun grabFauxWrittenFileBytes(initialData: KVNFileData): ByteArray {
      var capturedBytes: ByteArray? = null
      ByteArrayOutputStream().use { outputStream ->
        KVNFileReadWriter.writeToDisk(
          initialData,
          BufferedOutputStream(outputStream)
        )
        capturedBytes = outputStream.toByteArray()
      }.also {
        return capturedBytes!!
      }
    }
  }

  @Test
  fun test_readMetadata() {
    val initialData: KVNFileData = TestDataProvider.generateDecryptedKVNData(VERSION_STRING_TESTED_HERE)
    val readMetadata: KVNFileMetadata = KVNFileReadWriter.parseMetadataFromBytes(
      initialData.metadata.versionString,
      BufferedInputStream(ByteArrayInputStream(grabFauxWrittenFileBytes(initialData))).apply { skipNBytes(8) }
    )
    assertEquals(initialData.metadata.uuid, readMetadata.uuid)
    assertEquals(initialData.metadata.dateCreated, readMetadata.dateCreated)
    assertEquals(initialData.metadata.dateModified, readMetadata.dateModified)
    assertContentEquals(initialData.metadata.category, readMetadata.category)
    assertContentEquals(initialData.metadata.nametag, readMetadata.nametag)
    assertEquals(initialData.metadata.versionString, readMetadata.versionString)
  }

  @Test
  fun test_readEncryptedData() {
    val initialData: KVNFileData = TestDataProvider.generateDecryptedKVNData(VERSION_STRING_TESTED_HERE)
    val readMetadata: KVNFileMetadata = KVNFileReadWriter.parseMetadataFromBytes(
      initialData.metadata.versionString,
      BufferedInputStream(ByteArrayInputStream(grabFauxWrittenFileBytes(initialData))).apply { skipNBytes(8) }
    )
    val readData: KVNFileData = KVNFileReadWriter.decryptWithKnownData(
      readMetadata,
      BufferedInputStream(ByteArrayInputStream(grabFauxWrittenFileBytes(initialData))),
      "test".toCharArray())

    assertContentEquals(
      initialData.encKeyValue!!.serializeToBytes(),
      readData.encKeyValue!!.serializeToBytes()
    )
    assertContentEquals(
      initialData.decryptedValue!!.getProvider().get(),
      readData.decryptedValue!!.getProvider().get()
    )
  }

}