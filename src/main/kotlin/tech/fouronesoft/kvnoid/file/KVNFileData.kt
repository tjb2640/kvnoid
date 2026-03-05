package tech.fouronesoft.kvnoid.file

import tech.fouronesoft.kvnoid.encryption.AESGCMKey
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.time.Clock

/**
 * KVN data model and helpers for reading and writing.
 * Should be agnostic to file format versioning
 */
data class KVNFileData (
  var metadata: KVNFileMetadata,
  var keyData: AESGCMKey? = null,
  var decryptedV: ByteArray = ByteArray(1),
) {

  /**
   * Writes this KVN file to the disk.
   * Right now it uses the file spec version found in its own data. I might default to writing using the
   * newest version as this progresses...
   *
   * @param absPath file path to write to
   * @passphrase passphrase (some kind of plaintext master key) to encrypt the file with
   */
  fun writeToDisk(absPath: String, passphrase: String) {
    this.keyData = this.keyData ?: AESGCMKey.fromNewPlaintextPassphrase(passphrase.toCharArray())
    this.metadata.dateModified = Clock.System.now()

    try {
      BufferedOutputStream(File(absPath).outputStream()).use { writer ->
        KVNFileReadWriter.writeToDisk(this, writer)
      }
    } catch (e: Exception) {
      // TODO
      throw e
    }
  }

  companion object {
    /**
     * Concatenates a little-endian (assumed) version number byte array to a String.
     *
     * The bytes are converted as decimal literals (they represent base-10 century, year, month and day).
     *
     * @param versionBytes ByteArray(4) version bytes
     * @throws RuntimeException if versionBytes parameter is not 4 bytes in size
     */
    fun versionBytesToString(versionBytes: ByteArray): String {
      require (versionBytes.size == 4) {
        "Version bytes array must always be 4 bytes in size"
      }
      val century = versionBytes[0].toString().padStart(2, '0')
      val year = versionBytes[1].toString().padStart(2, '0')
      val month = versionBytes[2].toString().padStart(2, '0')
      val day = versionBytes[3].toString().padStart(2, '0')
      return "$century$year$month$day"
    }

    /**
     * Takes a serialized version number string and swings it back to a ByteArray.
     * Accepts a 10-long string with 4 bytes of radix 10 in literal form
     *
     * @param versionString e.g. 20260102
     * @return ByteArray special representation
     */
    fun versionStringToBytes(versionString: String): ByteArray {
      require (versionString.length == 8) {
        "Bad length for version string, expected 8, got ${versionString.length}"
      }
      val byteBuf: ByteBuffer = ByteBuffer.allocate(4)
      for (i in 0..3) {
        byteBuf.put(versionString
          .substring((i * 2), (i * 2) + 2)
          .toByte(10))
      }
      return byteBuf.array()
    }

    /**
     * Reads a KVN file given its absolute path on the disk.
     *
     * @param absPath path to file
     * @param passphrase used to generate the decryption key and unlock the file
     */
    fun readFromAbsolutePath(
        absPath: String,
        metadata: KVNFileMetadata,
        passphrase: CharArray): KVNFileData? {

      try {
        BufferedInputStream(File(absPath).inputStream()).use { reader ->
          return KVNFileReadWriter.decryptWithKnownData(
              metadata = metadata,
              restOfFile = reader,
              passphrase = passphrase)
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

    other as KVNFileData

    if (metadata != other.metadata) return false
    if (keyData != other.keyData) return false
    if (!decryptedV.contentEquals(other.decryptedV)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = metadata.hashCode()
    result = 31 * result + (keyData?.hashCode() ?: 0)
    result = 31 * result + decryptedV.contentHashCode()
    return result
  }

}