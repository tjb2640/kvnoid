package tech.fouronesoft.kvnoid.file

import tech.fouronesoft.kvnoid.encryption.AESGCMKey
import tech.fouronesoft.kvnoid.file.spec.KVNFileSpec202602167f
import tech.fouronesoft.kvnoid.file.spec.pieces.KVNHeader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.UUID
import kotlin.time.Instant

class DecryptedKVNData(
  val uuid: UUID?,
  val versionString: String,
  val dateCreated: Instant,
  val dateModified: Instant?,
  val category: String,
  val nametag: String,
  val keyData: AESGCMKey?,
  val decryptedK: String?,
  val decryptedV: String?,
) {

  fun writeToDisk(absPath: String, passphrase: String) {
    var f: File
    try {
      BufferedOutputStream(File(absPath).outputStream()).use { writer ->
        // TODO
      }
    } catch (e: Exception) {
      // TODO
      throw e
    }
  }

  companion object {
    fun fromAbsolutePath(absPath: String, passphrase: String): DecryptedKVNData? {
      // We only care here about the magic bytes and version (always 7b and 5b respectively)
      val headerBytesMagic = ByteArray(KVNHeader.KVNFILE_SIZE_HEADER_MAGIC)
      val headerBytesVersion = ByteArray(KVNHeader.KVNFILE_SIZE_HEADER_VERSION)

      try {
        BufferedInputStream(File(absPath).inputStream()).use { reader ->
          reader.read(headerBytesMagic)
          require(headerBytesMagic.contentEquals(KVNHeader.KVNFILE_HEADER_MAGIC)) { "File is not a KVN file" }
          reader.read(headerBytesVersion)

          // Check version bytes - excluding revision
          // Want to split out reading the rest of the file based on this value
          val headerStringVersion: String = KVNHeader.versionBytesToString(headerBytesVersion)
          when (headerStringVersion.substring(0..7)) {
            "20260216" -> return KVNFileSpec202602167f.parseFromBytes(
              restOfFile = reader,
              passphrase = passphrase)
            else -> throw RuntimeException("Unable to read file version $headerStringVersion")
          }
        }
      } catch (e: Exception) {
        // TODO: Tighten up
      }
      return null
    }
  }

}