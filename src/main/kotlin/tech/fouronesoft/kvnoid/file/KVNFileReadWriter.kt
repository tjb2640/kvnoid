package tech.fouronesoft.kvnoid.file

import tech.fouronesoft.kvnoid.encryption.AESGCMKey
import tech.fouronesoft.kvnoid.util.DataSerializationUtils
import tech.fouronesoft.kvnoid.util.ObfuscatedString
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.CRC32
import kotlin.collections.forEachIndexed
import kotlin.time.Instant

const val ZERO_BYTE: Byte = 0.toByte()

class KVNFileReadWriter {
  companion object {
    // Magic bytes found in every KVN file header. Should never change
    val KVNFILE_HEADER_MAGIC: ByteArray = byteArrayOf(
      0x95.toByte(), 'K'.code.toByte(), 'V'.code.toByte(), 'N'.code.toByte()
    )

    const val WRITE_VERSION_STRING: String = "20260216"
    val WRITE_VERSION_BYTES: ByteArray = KVNFileData.versionStringToBytes(WRITE_VERSION_STRING)
    const val SIZE_BYTES_PADDING: Int = 4
    const val BYTELIMIT_CATEGORY: Int = 128
    const val BYTELIMIT_NAMETAG: Int = 128
    const val BYTELIMIT_V: Int = 2048

    fun parseMetadataFromBytes(
      fileVersion: String, restOfFile: BufferedInputStream
    ): KVNFileMetadata {

      val crc = CRC32()
      var bytesRead = 4 + 4 // magic bytes and version code

      /**
       * Consumes padding bytes and verifies they are all null; counts bytes read
       */
      val verifyNullPadding = fun() {
        restOfFile.readNBytes(SIZE_BYTES_PADDING).forEach { b ->
          b.takeIf { ZERO_BYTE == b } ?: throw RuntimeException("Nonnull byte in null-padding region, bad data?")
        }
        bytesRead += SIZE_BYTES_PADDING
      }

      /**
       * Keeps track of bytes read and updates CRC **before** returning the read byte array
       */
      val readNBytes = fun(len: Int): ByteArray {
        bytesRead += len
        restOfFile.readNBytes(len).also {
          crc.update(it, 0, len)
          return it
        }
      }

      // Read header data
      val bytesUUIDMostSig = readNBytes(Long.SIZE_BYTES)
      val bytesUUIDLeastSig = readNBytes(Long.SIZE_BYTES)
      val bytesDateCreated = readNBytes(Long.SIZE_BYTES)
      val bytesDateModified = readNBytes(Long.SIZE_BYTES)
      readNBytes(24) // Skip 24 reserved bytes
      val lenCategory: Int = DataSerializationUtils.byteArrayToIntLE(readNBytes(Int.SIZE_BYTES))
      val lenNametag: Int = DataSerializationUtils.byteArrayToIntLE(readNBytes(Int.SIZE_BYTES))
      val lenKeyData: Int = DataSerializationUtils.byteArrayToIntLE(readNBytes(Int.SIZE_BYTES))
      val lenEncryptedV: Int = DataSerializationUtils.byteArrayToIntLE(readNBytes(Int.SIZE_BYTES))
      readNBytes(52) // Skip 52 reserved bytes
      val bytesCategory = readNBytes(lenCategory)
      val bytesNametag = readNBytes(lenNametag)
      val crcFromFile: Long = DataSerializationUtils.byteArrayToLongLE(
        restOfFile.readNBytes(Long.SIZE_BYTES)
      )
      bytesRead += Long.SIZE_BYTES

      // Verify CRC
      require(crcFromFile == crc.value) {
        "CRC mismatch: file specified '${crcFromFile.toHexString()}', calculated '${crc.value.toHexString()}'"
      }
      verifyNullPadding()

      return KVNFileMetadata(
        uuid = UUID(
          DataSerializationUtils.byteArrayToLongLE(bytesUUIDMostSig),
          DataSerializationUtils.byteArrayToLongLE(bytesUUIDLeastSig)
        ),
        versionString = fileVersion,
        dateCreated = Instant.fromEpochMilliseconds(DataSerializationUtils.byteArrayToLongLE(bytesDateCreated)),
        dateModified = Instant.fromEpochMilliseconds(DataSerializationUtils.byteArrayToLongLE(bytesDateModified)),
        category = bytesCategory,
        nametag = bytesNametag,
        // These are temporal, I calculate them for convenience, but will need to reread the metadata from the file
        // whenever the category or nametag properties change after a disk write.
        keyDataLength = lenKeyData,
        keyDataPosition = bytesRead,
        encryptedVLength = lenEncryptedV
      )
    }

    /**
     * Called from `LoadedKVNData.readFromAbsolutePath` after verifying header and version info.
     * TODO
     */
    fun decryptWithKnownData(
      metadata: KVNFileMetadata, buffer: BufferedInputStream, passphrase: CharArray
    ): KVNFileData {

      // Skip number of bytes read up to this point
      buffer.skipNBytes(metadata.keyDataPosition.toLong())

      val keyData = AESGCMKey.fromSerializedBytes(passphrase, buffer.readNBytes(metadata.keyDataLength))
      keyData.decrypt(buffer.readNBytes(metadata.encryptedVLength)).also { decryptedBlock ->
        return KVNFileData(
          metadata = metadata, keyData = keyData, decryptedV = ObfuscatedString(
            initialValue = decryptedBlock, overwriteInitialValueSource = true
          )
        )
      }
    }

    /**
     * Called from `LoadedKVNData.writeToDisk` after verifying header and version info.
     *
     * @param contents the LoadedKVNData instance to write
     * @param writer BufferedOutputStream pointing to the file
     * @see decryptWithKnownData
     * @see KVNFileData.writeToDisk
     */
    fun writeToDisk(
      contents: KVNFileData, writer: BufferedOutputStream
    ) {

      requireNotNull(contents.keyData) { "Key data was not initialized" }
      requireNotNull(contents.decryptedV) { "No value to encrypt" }
      require(contents.metadata.category.size <= BYTELIMIT_CATEGORY) {
        "Category is limited to $BYTELIMIT_CATEGORY bytes in size; currently ${contents.metadata.category.size}"
      }
      require(contents.metadata.nametag.size <= BYTELIMIT_NAMETAG) {
        "Name tag is limited to $BYTELIMIT_NAMETAG bytes in size; currently ${contents.metadata.nametag.size}"
      }
      require(contents.decryptedV!!.getLength() <= BYTELIMIT_V) {
        "Stored value is limited to $BYTELIMIT_V bytes in size; currently ${contents.decryptedV!!.getLength()}"
      }

      // Encryption action
      val serializedKeyBytes: ByteArray = contents.keyData!!.serializeToBytes()
      val encryptedV: ByteArray = contents.decryptedV!!.getProvider().use { provider ->
        val inputBytes = ByteArray(provider.get().size)
        provider.get().forEachIndexed { index, ch ->
          inputBytes[index] = ch.code.toByte()
        }
        contents.keyData!!.encrypt(inputBytes).also {
          inputBytes.forEachIndexed { index, _ ->
            inputBytes[index] = 0.toByte()
          }
        }
      }

      // Lengths (ints) as specified in the header spec
      val lenCategory: Int = contents.metadata.category.size
      val lenNametag: Int = contents.metadata.nametag.size
      val lenKeyBytes: Int = serializedKeyBytes.size
      val lenEV: Int = encryptedV.size

      // Calc lengths of both sections
      val lenHeader = (4 + 4) +   // magic bytes and version string
          (2 * Long.SIZE_BYTES) + // UUID bytes
          (2 * Long.SIZE_BYTES) + // MS dates (longs)
          24 +                    // Reserved 24
          (4 * Int.SIZE_BYTES) +  // All written length values (ints)
          52 +                    // Reserved 52
          lenCategory + lenNametag + Long.SIZE_BYTES +       // CRC32
          SIZE_BYTES_PADDING
      val lenBody = lenKeyBytes + lenEV + SIZE_BYTES_PADDING

      // Helpers
      val crc = CRC32()
      val bufInt = ByteBuffer.allocate(Int.SIZE_BYTES).order(DataSerializationUtils.STANDARD_BYTE_ORDER)
      val bufLong = ByteBuffer.allocate(Long.SIZE_BYTES).order(DataSerializationUtils.STANDARD_BYTE_ORDER)

      val writeInt: ((Int) -> Unit) = fun(z) {
        bufInt.rewind().apply { putInt(z) }.array().also {
          writer.write(it)
          crc.update(it, 0, it.size)
        }
      }

      val writeLong: ((Long) -> Unit) = fun(l) {
        bufLong.rewind().apply { putLong(l) }.array().also {
          writer.write(it)
          crc.update(it, 0, it.size)
        }
      }

      val writeBytes: ((bytes: ByteArray) -> Unit) = fun(bytes) {
        writer.write(bytes)
        crc.update(bytes)
      }

      // Write header content
      writer.write(KVNFILE_HEADER_MAGIC)
      writer.write(WRITE_VERSION_BYTES)
      writeLong(contents.metadata.uuid.mostSignificantBits)
      writeLong(contents.metadata.uuid.leastSignificantBits)
      writeLong(contents.metadata.dateCreated.toEpochMilliseconds())
      writeLong(contents.metadata.dateModified.toEpochMilliseconds())
      writeBytes(ByteArray(24)) // Reserved
      writeInt(lenCategory)
      writeInt(lenNametag)
      writeInt(lenKeyBytes)
      writeInt(lenEV)
      writeBytes(ByteArray(52)) // Reserved
      writeBytes(contents.metadata.category)
      writeBytes(contents.metadata.nametag)
      writeLong(crc.value)
      writeBytes(ByteArray(SIZE_BYTES_PADDING))

      // Write body content (key bytes and encrypted data)
      writeBytes(serializedKeyBytes)
      writeBytes(encryptedV)
      writeBytes(ByteArray(SIZE_BYTES_PADDING))
      if ((lenHeader + lenBody) % 4 != 0) {
        writer.write(ByteArray(4 - ((lenHeader + lenBody) % 4)))
      }

      writer.flush()
    }
  }
}