package tech.fouronesoft.kvnoid.file.spec

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.fouronesoft.kvnoid.encryption.AESGCMKey
import tech.fouronesoft.kvnoid.file.DecryptedKVNData
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.time.Instant

class KVNFileSpec202602167fTest {

  var generatedKey: AESGCMKey? = null

  fun generateDecryptedKVNData(): DecryptedKVNData {
    // This'll keep IV and AAD the same
    generatedKey = generatedKey ?: AESGCMKey.fromNewPlaintextPassphrase("test")

    return DecryptedKVNData(
      uuid = UUID.randomUUID(), // not necessarily testing against this
      versionString = "202602167f",
      dateCreated = Instant.fromEpochMilliseconds(3000),
      dateModified = Instant.fromEpochMilliseconds(9001),
      category = "dummy_category",
      nametag = "dummy_nametag",
      keyData = generatedKey,
      decryptedK = "sample k",
      decryptedV = "sample v"
    )
  }

  @Test
  fun test_companion_writeToDisk_throwOnNullKeyData() {
    val decryptedKVNData: DecryptedKVNData = generateDecryptedKVNData()
    val fakeOutputStream = BufferedOutputStream(BufferedOutputStream.nullOutputStream())

    decryptedKVNData.keyData = null
    assertThrows<RuntimeException>("Should throw on null keyData") {
      KVNFileSpec202602167f.writeToDisk(decryptedKVNData, fakeOutputStream)
    }
  }

  @Test
  fun test_companion_writeToDisk_throwOnNullDecryptedK() {
    val decryptedKVNData: DecryptedKVNData = generateDecryptedKVNData()
    val fakeOutputStream = BufferedOutputStream(BufferedOutputStream.nullOutputStream())

    decryptedKVNData.decryptedK = null
    assertThrows<RuntimeException>("Should throw on a null decryptedK value") {
      KVNFileSpec202602167f.writeToDisk(decryptedKVNData, fakeOutputStream)
    }
  }

  @Test
  fun test_companion_writeToDisk_throwOnNullDecryptedV() {
    val decryptedKVNData: DecryptedKVNData = generateDecryptedKVNData()
    val fakeOutputStream = BufferedOutputStream(BufferedOutputStream.nullOutputStream())

    decryptedKVNData.decryptedV = null
    assertThrows<RuntimeException>("Should throw on a null decryptedV value") {
      KVNFileSpec202602167f.writeToDisk(decryptedKVNData, fakeOutputStream)
    }
  }

  @Test
  fun test_companion_writeToDisk_testDataIntegrity() {
    val decryptedKVNData: DecryptedKVNData = generateDecryptedKVNData()

    // Capture output to a B[]OS
    val capturedOutputStream = ByteArrayOutputStream()
    KVNFileSpec202602167f.writeToDisk(
      decryptedKVNData,
      BufferedOutputStream(capturedOutputStream))

    // TODO
    assertEquals('F'.code.toByte(),capturedOutputStream.toByteArray()[3])
  }
}