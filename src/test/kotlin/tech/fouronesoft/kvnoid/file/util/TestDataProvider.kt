package tech.fouronesoft.kvnoid.file.util

import tech.fouronesoft.kvnoid.encryption.AESGCMKey
import tech.fouronesoft.kvnoid.file.KVNFileData
import tech.fouronesoft.kvnoid.file.KVNFileMetadata
import tech.fouronesoft.kvnoid.util.DataSerializationUtils
import tech.fouronesoft.kvnoid.util.ObfuscatedString
import java.util.UUID
import kotlin.time.Instant

class TestDataProvider {

  // Ensure we use the same key between tests
  class GeneratedTestKey private constructor() {
    companion object {
      val PASSPHRASE: CharArray = "test".toCharArray()
      val instance: AESGCMKey by lazy { AESGCMKey.fromNewPlaintextPassphrase(PASSPHRASE) }
    }
  }

  companion object {
    val dummyUUID: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    val dummyCategory: String = "dummy_category"
    val dummyNametag: String = "dummy_nametag"
    val dummyDecryptedV: String = "sample_v"

    fun generateKVNMetadata(versionString: String): KVNFileMetadata {
      return KVNFileMetadata(
        uuid = dummyUUID,
        versionString = versionString,
        dateCreated = Instant.fromEpochMilliseconds(3000),
        dateModified = Instant.fromEpochMilliseconds(9001),
        category = DataSerializationUtils.stringToUTF8ByteArray(dummyCategory),
        nametag = DataSerializationUtils.stringToUTF8ByteArray(dummyNametag),
        keyDataLength = GeneratedTestKey.instance.serializeToBytes().size,
        keyDataPosition = -1,
        encryptedVLength = -1
      )
    }

    fun generateDecryptedKVNData(versionString: String): KVNFileData {
      return KVNFileData(
        metadata = generateKVNMetadata(versionString),
        keyData = GeneratedTestKey.instance,
        decryptedV = ObfuscatedString(DataSerializationUtils.stringToUTF8ByteArray(dummyDecryptedV))
      )
    }
  }
}