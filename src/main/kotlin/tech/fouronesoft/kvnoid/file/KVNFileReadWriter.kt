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
      fileVersion: String, restOfFile: BufferedInputStream, vaultKey: ObfuscatedString
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
      val lenCategoryKey: Int = DataSerializationUtils.byteArrayToIntLE(readNBytes(Int.SIZE_BYTES))
      val lenEncryptedCategory: Int = DataSerializationUtils.byteArrayToIntLE(readNBytes(Int.SIZE_BYTES))
      val lenNametagKey: Int = DataSerializationUtils.byteArrayToIntLE(readNBytes(Int.SIZE_BYTES))
      val lenEncryptedNametag: Int = DataSerializationUtils.byteArrayToIntLE(readNBytes(Int.SIZE_BYTES))
      val lenValueKey: Int = DataSerializationUtils.byteArrayToIntLE(readNBytes(Int.SIZE_BYTES))
      val lenEncryptedValue: Int = DataSerializationUtils.byteArrayToIntLE(readNBytes(Int.SIZE_BYTES))
      readNBytes(52) // Skip 52 reserved bytes
      val crcFromFile: Long = DataSerializationUtils.byteArrayToLongLE(restOfFile.readNBytes(Long.SIZE_BYTES))
      bytesRead += Long.SIZE_BYTES

      // Verify CRC
      require(crcFromFile == crc.value) {
        "CRC mismatch: file specified '${crcFromFile.toHexString()}', calculated '${crc.value.toHexString()}'"
      }
      verifyNullPadding()

      // Read category
      val encKeyCategory = vaultKey.getProvider().use { provider ->
        AESGCMKey.fromSerializedBytes(
          passphrase = provider.get(),
          bytes = readNBytes(lenCategoryKey)) }
      val decryptedCategory = ObfuscatedString(initialValue = encKeyCategory.decrypt(
        readNBytes(lenEncryptedCategory)), overwriteInitialValueSource = true)

      // Read nametag
      val encKeyNametag = vaultKey.getProvider().use { provider ->
        AESGCMKey.fromSerializedBytes(
          passphrase = provider.get(),
          bytes = readNBytes(lenNametagKey)) }
      val decryptedNametag = ObfuscatedString(initialValue = encKeyNametag.decrypt(
        readNBytes(lenEncryptedNametag)), overwriteInitialValueSource = true)

      return KVNFileMetadata(
        uuid = UUID(
          DataSerializationUtils.byteArrayToLongLE(bytesUUIDMostSig),
          DataSerializationUtils.byteArrayToLongLE(bytesUUIDLeastSig)
        ),
        versionString = fileVersion,
        dateCreated = Instant.fromEpochMilliseconds(DataSerializationUtils.byteArrayToLongLE(bytesDateCreated)),
        dateModified = Instant.fromEpochMilliseconds(DataSerializationUtils.byteArrayToLongLE(bytesDateModified)),
        encKeyCategory = encKeyCategory,
        decryptedCategory = decryptedCategory,
        encKeyNametag = encKeyNametag,
        decryptedNametag = decryptedNametag,
        // These are temporal, I calculate them for convenience, but will need to reread the metadata from the file
        // whenever the category or nametag properties change after a disk write.
        encKeyValueLength = lenValueKey,
        encKeyValuePosition = bytesRead,
        encryptedValueLength = lenEncryptedValue
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
      buffer.skipNBytes(metadata.encKeyValuePosition.toLong())

      val keyData = AESGCMKey.fromSerializedBytes(passphrase, buffer.readNBytes(metadata.encKeyValueLength))
      keyData.decrypt(buffer.readNBytes(metadata.encryptedValueLength)).also { decryptedBlock ->
        return KVNFileData(
          metadata = metadata, encKeyValue = keyData, decryptedValue = ObfuscatedString(
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

      requireNotNull(contents.encKeyValue) { "Key data was not initialized" }
      requireNotNull(contents.decryptedValue) { "No value to encrypt" }
      require(contents.metadata.decryptedCategory!!.getLength() <= BYTELIMIT_CATEGORY) {
        "Category is limited to $BYTELIMIT_CATEGORY bytes in size; currently ${contents.metadata.decryptedCategory!!.getLength()}"
      }
      require(contents.metadata.decryptedNametag!!.getLength() <= BYTELIMIT_NAMETAG) {
        "Name tag is limited to $BYTELIMIT_NAMETAG bytes in size; currently ${contents.metadata.decryptedNametag!!.getLength()}"
      }
      require(contents.decryptedValue!!.getLength() <= BYTELIMIT_V) {
        "Stored value is limited to $BYTELIMIT_V bytes in size; currently ${contents.decryptedValue!!.getLength()}"
      }

      // Encryption action
      val serializedCategoryKeyBytes: ByteArray = contents.metadata.encKeyCategory!!.serializeToBytes()
      val serializedNametagKeyBytes: ByteArray = contents.metadata.encKeyNametag!!.serializeToBytes()
      val serializedValueKeyBytes: ByteArray = contents.encKeyValue!!.serializeToBytes()

      val encryptedCategory: ByteArray = contents.metadata.decryptedCategory!!.getProvider().use { provider ->
        val inputBytes = ByteArray(provider.get().size)
        provider.get().forEachIndexed { index, ch -> inputBytes[index] = ch.code.toByte() }
        contents.metadata.encKeyCategory!!.encrypt(inputBytes).also {
          inputBytes.forEachIndexed { index, _ -> inputBytes[index] = 0.toByte() }
        }
      }

      val encryptedNametag: ByteArray = contents.metadata.decryptedNametag!!.getProvider().use { provider ->
        val inputBytes = ByteArray(provider.get().size)
        provider.get().forEachIndexed { index, ch -> inputBytes[index] = ch.code.toByte() }
        contents.metadata.encKeyNametag!!.encrypt(inputBytes).also {
          inputBytes.forEachIndexed { index, _ -> inputBytes[index] = 0.toByte() }
        }
      }

      val encryptedValue: ByteArray = contents.decryptedValue!!.getProvider().use { provider ->
        val inputBytes = ByteArray(provider.get().size)
        provider.get().forEachIndexed { index, ch -> inputBytes[index] = ch.code.toByte() }
        contents.encKeyValue!!.encrypt(inputBytes).also {
          inputBytes.forEachIndexed { index, _ -> inputBytes[index] = 0.toByte() }
        }
      }

      // Lengths (ints) as specified in the header spec
      val lenCategoryKey: Int = serializedCategoryKeyBytes.size
      val lenNametagKey: Int = serializedNametagKeyBytes.size
      val lenValueKey: Int = serializedValueKeyBytes.size
      val lenEncryptedCategory: Int = encryptedCategory.size
      val lenEncryptedNametag: Int = encryptedNametag.size
      val lenEncryptedValue: Int = encryptedValue.size

      // Calc lengths of both sections
      val lenHeader = (4 + 4) +   // magic bytes and version string
          (2 * Long.SIZE_BYTES) + // UUID bytes
          (2 * Long.SIZE_BYTES) + // MS dates (longs)
          24 +                    // Reserved 24
          (6 * Int.SIZE_BYTES) +  // All written length values (ints)
          52 +                    // Reserved 52
          Long.SIZE_BYTES +       // CRC32
          SIZE_BYTES_PADDING
      val lenBody = lenCategoryKey + lenEncryptedCategory +
          lenNametagKey + lenEncryptedNametag +
          lenValueKey + lenEncryptedValue +
          SIZE_BYTES_PADDING

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
      writeInt(lenCategoryKey)
      writeInt(lenEncryptedCategory)
      writeInt(lenNametagKey)
      writeInt(lenEncryptedNametag)
      writeInt(lenValueKey)
      writeInt(lenEncryptedValue)
      writeBytes(ByteArray(52)) // Reserved
      writeLong(crc.value)
      writeBytes(ByteArray(SIZE_BYTES_PADDING))

      // Write body content (key bytes and encrypted data)
      writeBytes(serializedCategoryKeyBytes)
      writeBytes(encryptedCategory)
      writeBytes(serializedNametagKeyBytes)
      writeBytes(encryptedNametag)
      writeBytes(serializedValueKeyBytes)
      writeBytes(encryptedValue)
      writeBytes(ByteArray(SIZE_BYTES_PADDING))
      if ((lenHeader + lenBody) % 4 != 0) {
        writer.write(ByteArray(4 - ((lenHeader + lenBody) % 4)))
      }

      writer.flush()
    }
  }
}