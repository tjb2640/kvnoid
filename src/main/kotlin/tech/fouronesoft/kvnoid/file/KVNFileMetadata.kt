package tech.fouronesoft.kvnoid.file

import tech.fouronesoft.kvnoid.file.LoadedKVNData.Companion.KVNFILE_HEADER_MAGIC
import tech.fouronesoft.kvnoid.file.LoadedKVNData.Companion.KVNFILE_SIZE_HEADER_MAGIC
import tech.fouronesoft.kvnoid.file.LoadedKVNData.Companion.KVNFILE_SIZE_HEADER_VERSION
import tech.fouronesoft.kvnoid.file.LoadedKVNData.Companion.versionBytesToString
import tech.fouronesoft.kvnoid.file.spec.KVNFileSpec202602167f
import java.io.BufferedInputStream
import java.io.File
import java.util.UUID
import kotlin.time.Instant

/**
 * Represents metadata about a KVN file.
 * Also stores some "information", i.e. category and nametag.
 */
data class KVNFileMetadata (
  val uuid: UUID,
  val versionString: String,
  val dateCreated: Instant,
  var dateModified: Instant,
  val category: ByteArray,
  val nametag: ByteArray,
  val keyDataLength: Int,
  val keyDataPosition: Int,
  val encryptedVLength: Int,
  val encryptedVPosition: Int
) {

  companion object {
    fun readFromAbsolutePath(absPath: String): KVNFileMetadata? {
      // We only care here about the magic bytes and version (always 7b and 5b respectively)
      val headerBytesMagic = ByteArray(KVNFILE_SIZE_HEADER_MAGIC)
      val headerBytesVersion = ByteArray(KVNFILE_SIZE_HEADER_VERSION)

      try {
        BufferedInputStream(File(absPath).inputStream()).use { reader ->
          reader.read(headerBytesMagic)
          require(headerBytesMagic.contentEquals(KVNFILE_HEADER_MAGIC)) { "File is not a KVN file" }
          reader.read(headerBytesVersion)

          // Check version bytes - excluding revision
          // Want to split out reading the rest of the file based on this value
          val headerStringVersion: String = versionBytesToString(headerBytesVersion)
          when (headerStringVersion.substring(0..7)) {
            "20260216" -> return KVNFileSpec202602167f.parseMetadataFromBytes(
              restOfFile = reader)
            else -> throw RuntimeException("Unable to read file metadata with version $headerStringVersion")
          }
        }
      } catch (_: Exception) {
        // TODO: Tighten up
      }
      return null
    }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as KVNFileMetadata

    if (uuid != other.uuid) return false
    if (versionString != other.versionString) return false
    if (dateCreated != other.dateCreated) return false
    if (dateModified != other.dateModified) return false
    if (!category.contentEquals(other.category)) return false
    if (!nametag.contentEquals(other.nametag)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = uuid.hashCode()
    result = 31 * result + versionString.hashCode()
    result = 31 * result + dateCreated.hashCode()
    result = 31 * result + dateModified.hashCode()
    result = 31 * result + category.contentHashCode()
    result = 31 * result + nametag.contentHashCode()
    return result
  }

}