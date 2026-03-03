package tech.fouronesoft.kvnoid.file.spec

import tech.fouronesoft.kvnoid.encryption.AESGCMKey
import tech.fouronesoft.kvnoid.file.KVNFileMetadata
import tech.fouronesoft.kvnoid.util.DataSerializationUtils
import tech.fouronesoft.kvnoid.file.LoadedKVNData
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.CRC32
import kotlin.time.Instant

/*
  File structure for this version (2026-02-16 rev 7f indev) TODO move this somewhere
  Header
    Magic bytes 7
    Version 5 (12)
    UUID 128 (140)
    date created ms 8 (148)
    date modified ms 8 (156)
    reserved bytes 24 (180)
    length of category 4 (184) c
    length of nametag 4 (188) n
    length of encryption key bytes 4 (192) x
    length of encrypted v 4 (196) z
    reserved bytes 52 (248)
    padding \0 x 8 (256)

  Body
    category (variable length c)
    padding \0 x4
    nametag (variable length n) (c+n)
    padding \0 x4
    crc32 to this point (8B)
    padding \0 x4
    encryption key bytes (variable length x) (c+n+x)
    padding \0 x4
    v (variable length z) (c+n+x+z) (ENCRYPTED)
    padding \0 variable length to 4 boundary

 */

/**
 * .kvn file spec version 2026/02/16 rev 7f
 */
class KVNFileSpec202602167f {
  companion object {
    const val VERSION_STRING: String = "202602167f"
    val VERSION_BYTES: ByteArray = LoadedKVNData.versionStringToBytes(VERSION_STRING)
    const val SIZE_BYTES_PADDING: Int = 4
    const val BYTELIMIT_CATEGORY: Int = 256
    const val BYTELIMIT_NAMETAG: Int = 512
    const val BYTELIMIT_V: Int = 32_000_000

    fun parseMetadataFromBytes(restOfFile: BufferedInputStream): KVNFileMetadata {
      val crc = CRC32()
      var bytesRead = 7 // TODO: this implies the existence of a lockfile. mark one!

      // Consume $PAD_SIZE bytes and verify all are null
      val verifyNullPadding = fun() {
        restOfFile.readNBytes(SIZE_BYTES_PADDING).forEach { b ->
          b.takeIf { 0x00.toByte() == b } ?: throw RuntimeException("Nonnull byte in padded region") // TODO
        }
        bytesRead += SIZE_BYTES_PADDING
      }

      val readNBytes = fun(len: Int): ByteArray {
        bytesRead += len
        return restOfFile.readNBytes(len)
      }

      // UUID
      val bytesUUIDMostSig = readNBytes(8)
      val bytesUUIDLeastSig = readNBytes(8)
      crc.update(bytesUUIDMostSig)
      crc.update(bytesUUIDLeastSig)

      // Dates created and modified
      val bytesDateCreated = readNBytes(8)
      val bytesDateModified = readNBytes(8)
      crc.update(bytesDateCreated)
      crc.update(bytesDateModified)

      // Skip 24 reserved bytes
      crc.update(readNBytes(24))

      // Grab a bunch of lengths
      val bytesLenCategory = readNBytes(4)
      val bytesLenNametag = readNBytes(4)
      val bytesLenKeyData = readNBytes(4)
      val bytesLenEncryptedV = readNBytes(4)
      val lenCategory: Int = DataSerializationUtils.byteArrayToIntLE(bytesLenCategory)
      val lenNametag: Int = DataSerializationUtils.byteArrayToIntLE(bytesLenNametag)
      val lenKeyData: Int = DataSerializationUtils.byteArrayToIntLE(bytesLenKeyData)
      val lenEncryptedV: Int = DataSerializationUtils.byteArrayToIntLE(bytesLenEncryptedV)
      crc.update(lenCategory)
      crc.update(lenNametag)
      crc.update(lenKeyData)
      crc.update(lenEncryptedV)

      // Skip 52 reserved bytes
      crc.update(readNBytes(52))

      // Padding $PAD_SIZEx \0?
      verifyNullPadding()

      // Should be done with header now - read a few things from the body
      val bytesCategory = readNBytes(lenCategory)
      verifyNullPadding()
      val bytesNametag = readNBytes(lenNametag)
      verifyNullPadding()

      // Check CRC
      val crcFromFile: Long = DataSerializationUtils.byteArrayToLongLE(readNBytes(8))
      require(crcFromFile == crc.value) {
        "CRC mismatch: file specified ${crcFromFile.toHexString()}, calculated ${crc.value.toHexString()}"
      }
      verifyNullPadding()

      return KVNFileMetadata(
        uuid = UUID(
          DataSerializationUtils.byteArrayToLongLE(bytesUUIDMostSig),
          DataSerializationUtils.byteArrayToLongLE(bytesUUIDLeastSig)),
        versionString = VERSION_STRING, // prefer revision specified by the reader
        dateCreated = Instant.fromEpochMilliseconds(DataSerializationUtils.byteArrayToLongLE(bytesDateCreated)),
        dateModified = Instant.fromEpochMilliseconds(DataSerializationUtils.byteArrayToLongLE(bytesDateModified)),
        category = bytesCategory,
        nametag = bytesNametag,
        // I calculate these here for convenience, but will need to reread the metadata from the file
        // whenever the category or nametag properties change after a disk write.
        keyDataLength = lenKeyData,
        keyDataPosition = bytesRead,
        encryptedVLength = lenEncryptedV,
        encryptedVPosition = bytesRead + lenKeyData + SIZE_BYTES_PADDING
      )
    }

    /**
     * Called from `LoadedKVNData.readFromAbsolutePath` after verifying header and version info.
     * TODO
     */
    fun parseFromBytes(
        metadata: KVNFileMetadata,
        restOfFile: BufferedInputStream,
        passphrase: CharArray): LoadedKVNData {

      // Skip number of bytes up to this point (we have already read 12 for magic bytes and version)
      restOfFile.skipNBytes(metadata.keyDataPosition.toLong() - 12)

      val bytesKeyData = restOfFile.readNBytes(metadata.keyDataLength)
      restOfFile.readNBytes(4).forEach { b ->
        b.takeIf { 0x00.toByte() == b } ?: throw RuntimeException("Nonnull byte in padded region")
      }
      val bytesEncryptedV = restOfFile.readNBytes(metadata.encryptedVLength)
      val keyData: AESGCMKey = AESGCMKey.fromSerializedBytes(passphrase, bytesKeyData)
      return LoadedKVNData(
        metadata = metadata,
        keyData = keyData,
        decryptedV = keyData.decrypt(bytesEncryptedV)
      )
    }



    /**
     * Called from `LoadedKVNData.writeToDisk` after verifying header and version info.
     *
     * @param contents the LoadedKVNData instance to write
     * @param writer BufferedOutputStream pointing to the file
     * @see parseFromBytes
     * @see LoadedKVNData.writeToDisk
     */
    fun writeToDisk(
        contents: LoadedKVNData,
        writer: BufferedOutputStream) {

      requireNotNull(contents.keyData) { "Key data was not initialized" }
      requireNotNull(contents.decryptedV) { "No value to encrypt" }
      require(contents.metadata.category.size <= BYTELIMIT_CATEGORY) {
        "Category is limited to $BYTELIMIT_CATEGORY bytes in size; currently ${contents.metadata.category.size}"
      }
      require(contents.metadata.nametag.size <= BYTELIMIT_NAMETAG) {
        "Name tag is limited to $BYTELIMIT_NAMETAG bytes in size; currently ${contents.metadata.nametag.size}"
      }
      require(contents.decryptedV.size <= BYTELIMIT_V) {
        "Stored value is limited to $BYTELIMIT_V bytes in size; currently ${contents.decryptedV.size}"
      }

      // Encryption action
      val serializedKeyBytes: ByteArray = contents.keyData!!.serializeToBytes()
      val encryptedV: ByteArray = contents.keyData!!.encrypt(contents.decryptedV)

      // Lengths (ints) as specified in the header spec
      val lenCategory: Int = contents.metadata.category.size
      val lenNametag: Int = contents.metadata.nametag.size
      val lenKeyBytes: Int = serializedKeyBytes.size
      val lenEV: Int = encryptedV.size

      // Calc lengths of both sections
      val lenHeader = LoadedKVNData.KVNFILE_SIZE_HEADER_MAGIC +
          VERSION_BYTES.size +
          (2 * 8)   // MS dates (longs)
          24 +      // Reserved 52
          (5 * 4) + // All written length values (ints)
          52 +      // Reserved 53
          SIZE_BYTES_PADDING
      val lenBody = lenCategory +
          SIZE_BYTES_PADDING +
          lenNametag +
          SIZE_BYTES_PADDING +
          8 + // CRC32 (unsigned)
          SIZE_BYTES_PADDING +
          lenKeyBytes +
          SIZE_BYTES_PADDING +
          lenEV

      // Helpers
      val crc = CRC32()
      val bufPadding = ByteArray(SIZE_BYTES_PADDING)
      val bufInt = ByteBuffer.allocate(4).order(DataSerializationUtils.STANDARD_BYTE_ORDER)
      val bufLong = ByteBuffer.allocate(8).order(DataSerializationUtils.STANDARD_BYTE_ORDER)

      val writeInt: ((Int) -> Unit) = fun(z) {
        bufInt.rewind()
        writer.write(bufInt.apply { putInt(z) }.array())
        crc.update(bufInt)
      }

      val writeLong: ((Long) -> Unit) = fun(l) {
        bufLong.rewind()
        writer.write(bufLong.apply { putLong(l) }.array())
        crc.update(bufLong)
      }

      val writeLongWithPadding: ((Long) -> Unit) = fun(l) {
        writeLong(l)
        writer.write(bufPadding)
      }

      val writeBytesNoPadding: ((bytes: ByteArray) -> Unit) = fun(bytes) {
        writer.write(bytes)
        crc.update(bytes)
      }

      val writeBytesWithPadding: ((bytes: ByteArray) -> Unit) = fun(bytes) {
        writeBytesNoPadding(bytes)
        writer.write(bufPadding)
      }

      // Write header content
      writer.write(LoadedKVNData.KVNFILE_HEADER_MAGIC)
      writer.write(VERSION_BYTES)
      writeLong(contents.metadata.uuid.mostSignificantBits)
      writeLong(contents.metadata.uuid.leastSignificantBits)
      writeLong(contents.metadata.dateCreated.toEpochMilliseconds())
      writeLong(contents.metadata.dateModified.toEpochMilliseconds())
      writeBytesNoPadding(ByteArray(24)) // Reserved
      writeInt(lenCategory)
      writeInt(lenNametag)
      writeInt(lenKeyBytes)
      writeInt(lenEV)
      writeBytesWithPadding(ByteArray(52)) // Reserved

      // Write body content
      writeBytesWithPadding(contents.metadata.category)
      writeBytesWithPadding(contents.metadata.nametag)
      writeLongWithPadding(crc.value)
      writeBytesWithPadding(serializedKeyBytes)
      writeBytesWithPadding(encryptedV)
      if ((lenHeader + lenBody) % 4 != 0) { writer.write(ByteArray((lenHeader + lenBody) % 4)) }

      writer.flush()
    }
  }
}