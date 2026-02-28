package tech.fouronesoft.kvnoid.file

import tech.fouronesoft.kvnoid.encryption.AESGCMKey
import tech.fouronesoft.kvnoid.file.spec.KVNFileSpec202602167f
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * KVN data model and helpers for reading and writing.
 * Should be agnostic to file format versioning
 * @property versionString first 8 characters determine the parsing class
 * @property dateCreated never changes
 * @property dateModified changes when file is saved
 * @property category arbitrary, stored plaintext
 * @property nametag same as category
 * @property decryptedV stored with AES-256-GCM encryption
 */
data class LoadedKVNData(
  val uuid: UUID,
  val versionString: String,
  val dateCreated: Instant,
  var dateModified: Instant,
  val category: ByteArray = ByteArray(1),
  val nametag: ByteArray = ByteArray(1),
  var keyData: AESGCMKey?,
  var decryptedV: ByteArray = ByteArray(1),
) {

  /**
   * Writes this KVN file to the disk.
   * Right now it uses the file spec version found in its own data. I might default to writing using the
   * newest version as this progresses...
   *
   * @param absPath file path to write to - TODO figure out how I wanna handle path validation
   * @passphrase passphrase (some kind of plaintext master key) to encrypt the file with
   */
  fun writeToDisk(absPath: String, passphrase: String) {
    // Make sure we have initialized properties marked as "mutable" (I'd rather have none mutable)
    // TODO maybe tighten that up later
    this.keyData = this.keyData ?: AESGCMKey.fromNewPlaintextPassphrase(passphrase.toCharArray())
    this.decryptedV = this.decryptedV
    this.dateModified = Clock.System.now()

    try {
      BufferedOutputStream(File(absPath).outputStream()).use { writer ->
        when (this.versionString.subSequence(0..7)) {
          "20260216" -> KVNFileSpec202602167f.writeToDisk(this, writer)
          else -> throw RuntimeException("Unable to read version ${this.versionString}")
        }
      }
    } catch (e: Exception) {
      // TODO
      throw e
    }
  }

  companion object {
    // Magic bytes found in every KVN file header. Should never change
    val KVNFILE_HEADER_MAGIC: ByteArray = byteArrayOf(
      'K'.code.toByte(), 'V'.code.toByte(), 'N'.code.toByte(), 'F'.code.toByte(),
      0x00, 0x00, 0x00
    )
    const val KVNFILE_SIZE_HEADER_MAGIC: Int = 7
    const val KVNFILE_SIZE_HEADER_VERSION: Int = 5

    /**
     * Concatenates a little-endian (assumed) version number byte array to a String.
     *
     * The first four bytes are converted as decimal literals
     * (they represent base-10 century, year, month and day),
     * and the last one (revision) becomes hex.
     *
     * @param versionBytes ByteArray(5) version bytes
     * @throws RuntimeException if versionBytes parameter is not 5 bytes in size
     */
    fun versionBytesToString(versionBytes: ByteArray): String {
      require (versionBytes.size == KVNFILE_SIZE_HEADER_VERSION) { "Version bytes must be 5 long" }
      val century = versionBytes[0].toString().padStart(2, '0')
      val year = versionBytes[1].toString().padStart(2, '0')
      val month = versionBytes[2].toString().padStart(2, '0')
      val day = versionBytes[3].toString().padStart(2, '0')
      val revision = versionBytes[4].toHexString().padStart(2, '0')
      return "$century$year$month$day$revision"
    }

    /**
     * Takes a serialized version number string and swings it back to a ByteArray.
     * Accepts a 10-long string with 4 bytes of radix 10 in literal form, then 1 byte in radix 16 literal.
     *
     * @param versionString e.g. 202601027f
     * @return ByteArray special representation
     */
    fun versionStringToBytes(versionString: String): ByteArray {
      require (versionString.length == KVNFILE_SIZE_HEADER_VERSION * 2) { "Bad length for version string" }
      val byteBuf: ByteBuffer = ByteBuffer.allocate(5)
      for (i in 0..8 step 2) {
        byteBuf.put(versionString
          .substring(i, i + 2)
          .toByte(if (i == 8) 16 else 10))
      }
      return byteBuf.array()
    }

    /**
     * Reads a KVN file given its absolute path on the disk.
     *
     * @param absPath path to flie
     * @param passphrase used to generate the decryption key and unlock the file
     */
    fun readFromAbsolutePath(absPath: String, passphrase: CharArray): LoadedKVNData? {
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
            "20260216" -> return KVNFileSpec202602167f.parseFromBytes(
              restOfFile = reader,
              passphrase = passphrase)
            else -> throw RuntimeException("Unable to read version $headerStringVersion")
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

    other as LoadedKVNData

    if (uuid != other.uuid) return false
    if (versionString != other.versionString) return false
    if (dateCreated != other.dateCreated) return false
    if (dateModified != other.dateModified) return false
    if (!category.contentEquals(other.category)) return false
    if (!nametag.contentEquals(other.nametag)) return false
    if (keyData != other.keyData) return false
    if (!decryptedV.contentEquals(other.decryptedV)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = uuid.hashCode()
    result = 31 * result + versionString.hashCode()
    result = 31 * result + dateCreated.hashCode()
    result = 31 * result + dateModified.hashCode()
    result = 31 * result + category.hashCode()
    result = 31 * result + nametag.hashCode()
    result = 31 * result + (keyData?.hashCode() ?: 0)
    result = 31 * result + decryptedV.contentHashCode()
    return result
  }

}