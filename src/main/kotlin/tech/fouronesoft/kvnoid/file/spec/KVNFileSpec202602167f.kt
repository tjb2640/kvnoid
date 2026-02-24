package tech.fouronesoft.kvnoid.file.spec

import tech.fouronesoft.kvnoid.encryption.AESGCMKey
import tech.fouronesoft.kvnoid.util.DataSerializationUtils
import tech.fouronesoft.kvnoid.util.DataSerializationUtils.Companion.byteArrayToUTF8StringLE
import tech.fouronesoft.kvnoid.file.LoadedKVNData
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.time.Instant

// TODO implement hashes and store UUID inside file

/*
  File structure for this version (2026-02-16 rev 7f indev) TODO move this somewhere
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

/**
 * .kvn file spec version 2026/02/16 rev 7f
 */
class KVNFileSpec202602167f {
  companion object {
    const val VERSION_STRING: String = "202602167f"
    val VERSION_BYTES: ByteArray = LoadedKVNData.versionStringToBytes(VERSION_STRING)
    const val PAD_SIZE: Int = 4
    const val BYTELIMIT_CATEGORY: Int = 256
    const val BYTELIMIT_NAMETAG: Int = 512
    const val BYTELIMIT_K: Int = 4096
    const val BYTELIMIT_V: Int = 32_000_000

    /**
     * Called from `LoadedKVNData.readFromAbsolutePath` after verifying header and version info.
     *
     * @param restOfFile use to read in the rest of the file beyond the first 12 bytes.
     * @param passphrase used for key derivation
     * @return LoadedKVNData
     * @see writeToDisk
     * @see LoadedKVNData.readFromAbsolutePath
     */
    fun parseFromBytes(
        restOfFile: BufferedInputStream,
        passphrase: CharArray): LoadedKVNData {

      // Read the next 4 bytes from the stream and convert to Int
      val readIntFromStream: (() -> Int) = {
        DataSerializationUtils.byteArrayToIntLE(restOfFile.readNBytes(4))
      }

      // Read the next 8 bytes from the stream and convert to Long
      val readLongFromStream: (() -> Long) = {
        DataSerializationUtils.byteArrayToLongLE(restOfFile.readNBytes(8))
      }

      // Consume $PAD_SIZE bytes and verify all are null
      val verifyNullPadding = fun() {
        val maybeNull = ByteArray(PAD_SIZE)
        restOfFile.read(maybeNull)
        maybeNull.forEach { b ->
          b.takeIf { 0x00.toByte() == b } ?: throw RuntimeException("Nonnull byte in padded region") // TODO
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
      // Padding $PAD_SIZEx \0?
      verifyNullPadding()

      // Should be done with header now - body
      val strCategory: String = byteArrayToUTF8StringLE(restOfFile.readNBytes(lenCategory))
      verifyNullPadding()
      val strNametag: String = byteArrayToUTF8StringLE(restOfFile.readNBytes(lenNametag))
      verifyNullPadding()
      val keyData: AESGCMKey = AESGCMKey.fromSerializedBytes(passphrase, restOfFile.readNBytes(lenKeyBytes))
      verifyNullPadding()
      val bytesEncryptedK: ByteArray = restOfFile.readNBytes(lenEncryptedK)
      verifyNullPadding()
      val bytesEncryptedV: ByteArray = restOfFile.readNBytes(lenEncryptedV)
      verifyNullPadding()
      // There will be more bytes after this but as long as we hit $PAD_SIZEx\0 here it's fine

      return LoadedKVNData(
        uuid = UUID.randomUUID(),
        versionString = VERSION_STRING, // prefer revision specified by the reader
        dateCreated = dateCreated,
        dateModified = dateModified,
        category = strCategory,
        nametag = strNametag,
        keyData = keyData,
        decryptedK = keyData.decryptToUTF8String(bytesEncryptedK),
        decryptedV = keyData.decryptToUTF8String(bytesEncryptedV)
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
      requireNotNull(contents.decryptedK) { "No key to encrypt" }
      requireNotNull(contents.decryptedV) { "No value to encrypt" }
      require(contents.category.length <= BYTELIMIT_CATEGORY) {
        "Category is limited to $BYTELIMIT_CATEGORY bytes in size; currently ${contents.category.length}"
      }
      require(contents.nametag.length <= BYTELIMIT_NAMETAG) {
        "Name tag is limited to $BYTELIMIT_NAMETAG bytes in size; currently ${contents.nametag.length}"
      }
      require(contents.decryptedK!!.length <= BYTELIMIT_K) {
        "Stored k/v key is limited to $BYTELIMIT_K bytes in size; currently ${contents.decryptedK!!.length}"
      }
      require(contents.decryptedV!!.length <= BYTELIMIT_V) {
        "Stored k/v value is limited to $BYTELIMIT_V bytes in size; currently ${contents.decryptedV!!.length}"
      }

      val serializedKeyBytes: ByteArray = contents.keyData!!.serializeToBytes()

      // Go ahead and encrypt K and V here
      val encryptedK: ByteArray = contents.keyData!!.encrypt(DataSerializationUtils.stringToUTF8ByteArray(contents.decryptedK!!))
      val encryptedV: ByteArray = contents.keyData!!.encrypt(DataSerializationUtils.stringToUTF8ByteArray(contents.decryptedV!!))

      // Lengths (ints) as specified in the header spec
      val lenCategory: Int = contents.category.length
      val lenNametag: Int = contents.nametag.length
      val lenKeyBytes: Int = serializedKeyBytes.size
      val lenEK: Int = encryptedK.size
      val lenEV: Int = encryptedV.size

      // Calc lengths of both sections
      val lenHeader = LoadedKVNData.KVNFILE_SIZE_HEADER_MAGIC +
          VERSION_BYTES.size +
          (2 * 8)   // MS dates (longs)
          24 +      // Reserved 52
          (5 * 4) + // All written length values (ints)
          52 +      // Reserved 53
          PAD_SIZE
      val lenBody = lenCategory +
          lenNametag +
          lenKeyBytes +
          lenEK +
          lenEV +
          (5 * PAD_SIZE)

      // Commonplace things
      val bytesPadding = ByteArray(PAD_SIZE)

      val intBuf = ByteBuffer.allocate(4).order(DataSerializationUtils.STANDARD_BYTE_ORDER)
      val writeInt: ((Int) -> Unit) = fun(z) {
        intBuf.rewind()
        writer.write(intBuf.apply { putInt(z) }.array())
      }

      val longBuf = ByteBuffer.allocate(8).order(DataSerializationUtils.STANDARD_BYTE_ORDER)
      val writeLong: ((Long) -> Unit) = fun(l) {
        longBuf.rewind()
        writer.write(longBuf.apply { putLong(l) }.array())
      }

      val writePadding: (() -> Unit) = fun() {
        writer.write(bytesPadding)
      }

      // Header content
      writer.write(LoadedKVNData.KVNFILE_HEADER_MAGIC)
      writer.write(VERSION_BYTES)
      writeLong(contents.dateCreated.toEpochMilliseconds())
      writeLong(contents.dateModified.toEpochMilliseconds())
      writer.write(ByteArray(24)) // Reserved
      writeInt(lenCategory)
      writeInt(lenNametag)
      writeInt(lenKeyBytes)
      writeInt(lenEK)
      writeInt(lenEV)
      writer.write(ByteArray(52)) // Reserved
      writer.write(bytesPadding)

      // Body content
      writer.write(DataSerializationUtils.stringToUTF8ByteArray(contents.category))
      writePadding()
      writer.write(DataSerializationUtils.stringToUTF8ByteArray(contents.nametag))
      writePadding()
      writer.write(serializedKeyBytes)
      writePadding()
      writer.write(encryptedK)
      writePadding()
      writer.write(encryptedV)
      writePadding()
      if ((lenHeader + lenBody) % 4 != 0) { writer.write(ByteArray((lenHeader + lenBody) % 4)) }

      writer.flush()
    }
  }
}