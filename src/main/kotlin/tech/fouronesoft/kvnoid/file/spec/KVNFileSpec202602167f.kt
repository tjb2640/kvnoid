package tech.fouronesoft.kvnoid.file.spec

import tech.fouronesoft.kvnoid.encryption.AESGCMKey
import tech.fouronesoft.kvnoid.file.DecryptedKVNData
import java.io.BufferedInputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.time.Instant

/*
  File structure for this version (2026-02-16 rev 7f indev)
  Header
    Magic bytes 7
    Version 5 (12)
    date created ms 8 (20)
    date modified ms 8 (28)
    reserved bytes 24 (52)
    length of category 4 (56) c
    length of nametag 4 (60) n
    length of encryption key bytes 4 (64) x
    length of encrypted k 4 (68) y
    length of encrypted v 4 (72) z
    reserved bytes 52 (124)
    padding \0 x 4 (128)

  Body
    category (variable length c)
    padding \0 x4
    nametag (variable length n) (c+n)
    padding \0 x4
    encryption key bytes (variable length x) (c+n+x)
    padding \0 x4
    k (variable length y) (c+n+x+y) (ENCRYPTED)
    padding \0 x4
    v (variable length z) (c+n+x+y+z) (ENCRYPTED)
    padding \0 x4
    padding \0 variable length to 4 boundary

 */

// TODO: Implement BYTELIMIT_ related things

class KVNFileSpec202602167f {
  companion object {
    const val VERSION_STRING: String = "202602167f"
    const val NULL_BYTE: Byte = 0x00.toByte()
    const val BYTELIMIT_CATEGORY: Int = 256
    const val BYTELIMIT_NAMETAG: Int = 512
    const val BYTELIMIT_KEYDATA: Int = 2048
    const val BYTELIMIT_K: Int = 4096
    const val BYTELIMIT_V: Int = 32_000_000

    fun parseFromBytes(
        restOfFile: BufferedInputStream,
        passphrase: String): DecryptedKVNData {

      // Read the next 4 bytes from the stream and convert to Int
      val readIntFromStream: (() -> Int) = {
        restOfFile.readNBytes(4).foldIndexed(0) {
            i, acc, byte -> acc or (byte.toInt() and 0xFF shl (i * 8))
        }
      }

      // Read the next 8 bytes from the stream and convert to Long
      val readLongFromStream: (() -> Long) = {
        restOfFile.readNBytes(8).foldIndexed(0L) {
          i, acc, byte -> acc or (byte.toLong() and 0xFF shl (i * 8))
        }
      }

      // Consume n bytes and verify all are null
      val verifyNullPadding: ((Int) -> Unit) = { n: Int ->
        val maybeNull = ByteArray(n)
        restOfFile.read(maybeNull)
        maybeNull.forEach { b ->
          b.takeIf { NULL_BYTE == b } ?: throw RuntimeException("Nonnull byte in padded region") // TODO
        }
      }

      // Dates created and modified
      val dateCreated: Instant = Instant.fromEpochMilliseconds(readLongFromStream())
      val dateModified: Instant = Instant.fromEpochMilliseconds(readLongFromStream())
      // Skip 24 reserved bytes
      restOfFile.skipNBytes(24L)
      // Grab a bunch of lengths
      val lenCategory: Int = readIntFromStream()
      val lenNametag: Int = readIntFromStream()
      val lenKeyBytes: Int = readIntFromStream()
      val lenEncryptedK: Int = readIntFromStream()
      val lenEncryptedV: Int = readIntFromStream()
      // Skip 52 reserved bytes
      restOfFile.skipNBytes(52L)
      // Padding 4x \0?
      verifyNullPadding(4)

      // Should be done with header now - body
      val strCategory: String = restOfFile.readNBytes(lenCategory).toString(StandardCharsets.UTF_8)
      verifyNullPadding(4)
      val strNametag: String = restOfFile.readNBytes(lenNametag).toString(StandardCharsets.UTF_8)
      verifyNullPadding(4)
      val keyData: AESGCMKey = AESGCMKey.fromSerializedBytes(passphrase, restOfFile.readNBytes(lenKeyBytes))
      verifyNullPadding(4)
      val bytesEncryptedK: ByteArray = restOfFile.readNBytes(lenEncryptedK)
      verifyNullPadding(4)
      val bytesEncryptedV: ByteArray = restOfFile.readNBytes(lenEncryptedV)
      verifyNullPadding(4)
      // There will be more bytes after this but as long as we hit 4x\0 here it's fine

      return DecryptedKVNData(
        uuid = UUID.randomUUID(),
        versionString = VERSION_STRING, // prefer version specified by the reader
        dateCreated = dateCreated,
        dateModified = dateModified,
        category = strCategory,
        nametag = strNametag,
        keyData = keyData,
        decryptedK = keyData.decrypt(bytesEncryptedK).toString(StandardCharsets.UTF_8),
        decryptedV = keyData.decrypt(bytesEncryptedV).toString(StandardCharsets.UTF_8)
      )
    }
  }
}