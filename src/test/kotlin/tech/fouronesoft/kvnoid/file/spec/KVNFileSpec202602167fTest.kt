package tech.fouronesoft.kvnoid.file.spec

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.fouronesoft.kvnoid.encryption.AESGCMKey
import tech.fouronesoft.kvnoid.util.DataSerializationUtils
import tech.fouronesoft.kvnoid.file.LoadedKVNData
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Instant

class KVNFileSpec202602167fTest {

  val dummyUUID: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

  // Ensure we use the same key between tests
  class GeneratedTestKey private constructor() {
    companion object {
      val PASSPHRASE: CharArray = "test".toCharArray()
      val instance: AESGCMKey by lazy { AESGCMKey.fromNewPlaintextPassphrase(PASSPHRASE) }
    }
  }

  fun generateDecryptedKVNData(): LoadedKVNData {
    return LoadedKVNData(
      uuid = dummyUUID,
      versionString = "202602167f",
      dateCreated = Instant.fromEpochMilliseconds(3000),
      dateModified = Instant.fromEpochMilliseconds(9001),
      category = "dummy_category",
      nametag = "dummy_nametag",
      keyData = GeneratedTestKey.instance,
      decryptedK = "sample k",
      decryptedV = "sample v"
    )
  }

  @Test
  fun test_companion_writeToDisk_throwOnNullKeyData() {
    val loadedKVNData: LoadedKVNData = generateDecryptedKVNData()
    val fakeOutputStream = BufferedOutputStream(BufferedOutputStream.nullOutputStream())

    loadedKVNData.keyData = null
    assertThrows<RuntimeException>("Should throw on null keyData") {
      KVNFileSpec202602167f.writeToDisk(loadedKVNData, fakeOutputStream)
    }
  }

  @Test
  fun test_companion_writeToDisk_testDataIntegrity() {
    val loadedKVNData: LoadedKVNData = generateDecryptedKVNData()

    // Capture output to a B[]OS
    val capturedOutputStream = ByteArrayOutputStream()
    KVNFileSpec202602167f.writeToDisk(
      loadedKVNData,
      BufferedOutputStream(capturedOutputStream))

    // Let's just check the entire file; stupid way to do this  -but it's not a lot of data anyway
    val outputReader = BufferedInputStream(ByteArrayInputStream(capturedOutputStream.toByteArray()))
    assertContentEquals(LoadedKVNData.KVNFILE_HEADER_MAGIC, outputReader.readNBytes(7))
    assertContentEquals(KVNFileSpec202602167f.VERSION_BYTES, outputReader.readNBytes(5))

    fun assertHasNullPaddingHere() {
      assertContentEquals(ByteArray(KVNFileSpec202602167f.PAD_SIZE), outputReader.readNBytes(4))
    }

    // Take a look at these longs (date created and modified)
    assertEquals(
      loadedKVNData.dateCreated.toEpochMilliseconds(),
      DataSerializationUtils.byteArrayToLongLE(outputReader.readNBytes(8)))
    assertEquals(
      loadedKVNData.dateModified.toEpochMilliseconds(),
      DataSerializationUtils.byteArrayToLongLE(outputReader.readNBytes(8)))

    // Reserved bytes should be null right now
    assertContentEquals(ByteArray(24), outputReader.readNBytes(24))

    // Lengths - integers
    val lenCategory = DataSerializationUtils.byteArrayToIntLE(outputReader.readNBytes(4))
    val lenNametag = DataSerializationUtils.byteArrayToIntLE(outputReader.readNBytes(4))
    val lenKeyData = DataSerializationUtils.byteArrayToIntLE(outputReader.readNBytes(4))
    val lenEncryptedK = DataSerializationUtils.byteArrayToIntLE(outputReader.readNBytes(4))
    val lenEncryptedV = DataSerializationUtils.byteArrayToIntLE(outputReader.readNBytes(4))
    assertEquals(
      loadedKVNData.category.length,
      lenCategory)
    assertEquals(
      loadedKVNData.nametag.length,
      lenNametag)
    assertEquals(
      loadedKVNData.keyData!!.serializeToBytes().size,
      lenKeyData)
    assertEquals(
      loadedKVNData.keyData!!.encrypt(DataSerializationUtils.stringToUTF8ByteArray(loadedKVNData.decryptedK)).size,
      lenEncryptedK)
    assertEquals(
      loadedKVNData.keyData!!.encrypt(DataSerializationUtils.stringToUTF8ByteArray(loadedKVNData.decryptedV)).size,
      lenEncryptedV)

    // Trailing reserved bytes should also be null; also test null padding (4B)
    assertContentEquals(ByteArray(52), outputReader.readNBytes(52))
    assertHasNullPaddingHere()

    // Body content
    // Testing data read from file with written length matches byte array in UTF-8
    assertContentEquals(
      DataSerializationUtils.stringToUTF8ByteArray(loadedKVNData.category),
      outputReader.readNBytes(lenCategory))
    assertHasNullPaddingHere()
    assertContentEquals(
      DataSerializationUtils.stringToUTF8ByteArray(loadedKVNData.nametag),
      outputReader.readNBytes(lenNametag))
    assertHasNullPaddingHere()
    assertContentEquals(
      loadedKVNData.keyData!!.serializeToBytes(),
      outputReader.readNBytes(lenKeyData))
    assertHasNullPaddingHere()
    assertContentEquals(
      loadedKVNData.keyData!!.encrypt(DataSerializationUtils.stringToUTF8ByteArray(loadedKVNData.decryptedK)),
      outputReader.readNBytes(lenEncryptedK))
    assertHasNullPaddingHere()
    assertContentEquals(
      loadedKVNData.keyData!!.encrypt(DataSerializationUtils.stringToUTF8ByteArray(loadedKVNData.decryptedV)),
      outputReader.readNBytes(lenEncryptedV))
    assertHasNullPaddingHere()
  }
}