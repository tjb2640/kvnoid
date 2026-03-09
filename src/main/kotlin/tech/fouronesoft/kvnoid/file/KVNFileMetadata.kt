package tech.fouronesoft.kvnoid.file

import tech.fouronesoft.kvnoid.file.KVNFileData.Companion.versionBytesToString
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
class KVNFileMetadata (
  val uuid: UUID = UUID.randomUUID(),
  val versionString: String = KVNFileReadWriter.WRITE_VERSION_STRING,
  val dateCreated: Instant = Clock.System.now(),
  var dateModified: Instant = Clock.System.now(),
  val category: ByteArray,
  val nametag: ByteArray,
  val keyDataLength: Int = 0,
  val keyDataPosition: Int = 0,
  val encryptedVLength: Int = 0,
  var filePath: Path? = null
) {

  companion object {
    fun readFromAbsolutePath(absPath: String): KVNFileMetadata? {
      try {
        BufferedInputStream(File(absPath).inputStream()).use { reader ->
          // Check magic bytes (always 4 bytes)
          // Then the next 4 bytes are the version string's bytes (in literal form)
          require(reader.readNBytes(4).contentEquals(KVNFileReadWriter.KVNFILE_HEADER_MAGIC)) {
            "File is not a KVN file"
          }
          return KVNFileReadWriter.parseMetadataFromBytes(
            fileVersion = versionBytesToString(reader.readNBytes(4)),
            restOfFile = reader
          ).also { it.filePath = Paths.get(absPath) }
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