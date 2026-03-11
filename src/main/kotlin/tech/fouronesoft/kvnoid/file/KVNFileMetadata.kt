package tech.fouronesoft.kvnoid.file

import tech.fouronesoft.kvnoid.encryption.AESGCMKey
import tech.fouronesoft.kvnoid.file.KVNFileData.Companion.versionBytesToString
import tech.fouronesoft.kvnoid.util.ObfuscatedString
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Represents metadata about a KVN file.
 * Also stores some "information", i.e. category and nametag.
 */
class KVNFileMetadata(
  val uuid: UUID = UUID.randomUUID(),
  val versionString: String = KVNFileReadWriter.WRITE_VERSION_STRING,
  val dateCreated: Instant = Clock.System.now(),
  var dateModified: Instant = dateCreated,
  var encKeyCategory: AESGCMKey? = null,
  var encKeyNametag: AESGCMKey? = null,
  var decryptedCategory: ObfuscatedString? = null,
  var decryptedNametag: ObfuscatedString? = null,
  // Temporal properties
  val encKeyValueLength: Int = 0,
  val encKeyValuePosition: Int = 0,
  val encryptedValueLength: Int = 0,
  var filePath: Path? = null
) {

  companion object {
    fun readFromAbsolutePath(absPath: String, vaultKey: ObfuscatedString): KVNFileMetadata? {
      try {
        BufferedInputStream(File(absPath).inputStream()).use { reader ->
          // Check magic bytes (always 4 bytes)
          // Then the next 4 bytes are the version string's bytes (in literal form)
          require(reader.readNBytes(4).contentEquals(KVNFileReadWriter.KVNFILE_HEADER_MAGIC)) {
            "File is not a KVN file"
          }
          return KVNFileReadWriter.parseMetadataFromBytes(
            fileVersion = versionBytesToString(reader.readNBytes(4)),
            restOfFile = reader,
            vaultKey = vaultKey
          ).also { it.filePath = Paths.get(absPath) }
        }
      } catch (_: Exception) {
        // TODO: Tighten up
      }
      return null
    }
  }

}