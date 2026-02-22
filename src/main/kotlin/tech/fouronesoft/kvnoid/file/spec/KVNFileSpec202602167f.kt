package tech.fouronesoft.kvnoid.file.spec

import tech.fouronesoft.kvnoid.encryption.AESGCMKey
import tech.fouronesoft.kvnoid.file.DecryptedKVNData
import tech.fouronesoft.kvnoid.file.spec.pieces.KVNHeader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.time.Instant

/*
  File structure for this version (2026-02-16 rev 7f indev)
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

// TODO: Implement BYTELIMIT_ related things

class KVNFileSpec202602167f {
  companion object {
    const val VERSION_STRING: String = "202602167f"
    val VERSION_BYTES: ByteArray = KVNHeader.versionStringToBytes(VERSION_STRING)
    const val NULL_BYTE: Byte = 0x00.toByte()
    const val PAD_SIZE: Int = 4
    const val BYTELIMIT_CATEGORY: Int = 256
    const val BYTELIMIT_NAMETAG: Int = 512
    const val BYTELIMIT_KEYDATA: Int = 2048
    const val BYTELIMIT_K: Int = 4096
    const val BYTELIMIT_V: Int = 32_000_000

    fun parseFromBytes(
        restOfFile: BufferedInputStream,
        passphrase: String): DecryptedKVNData {

      // Read the next 4 bytes from the stream and convert to Int
      val readIntFromStream: (() -> Int) = {
        restOfFile.readNBytes(4).foldIndexed(0) {
            i, acc, byte -> acc or (byte.toInt() and 0xFF shl (i * 8))
        }
      }

      // Read the next 8 bytes from the stream and convert to Long
      val readLongFromStream: (() -> Long) = {
        restOfFile.readNBytes(8).foldIndexed(0L) {
          i, acc, byte -> acc or (byte.toLong() and 0xFF shl (i * 8))
        }
      }

      // Consume $PAD_SIZE bytes and verify all are null
      val verifyNullPadding = fun() {
        val maybeNull = ByteArray(PAD_SIZE)
        restOfFile.read(maybeNull)
        maybeNull.forEach { b ->
          b.takeIf { NULL_BYTE == b } ?: throw RuntimeException("Nonnull byte in padded region") // TODO
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
      val strCategory: String = restOfFile.readNBytes(lenCategory).toString(Charsets.UTF_8)
      verifyNullPadding()
      val strNametag: String = restOfFile.readNBytes(lenNametag).toString(Charsets.UTF_8)
      verifyNullPadding()
      val keyData: AESGCMKey = AESGCMKey.fromSerializedBytes(passphrase, restOfFile.readNBytes(lenKeyBytes))
      verifyNullPadding()
      val bytesEncryptedK: ByteArray = restOfFile.readNBytes(lenEncryptedK)
      verifyNullPadding()
      val bytesEncryptedV: ByteArray = restOfFile.readNBytes(lenEncryptedV)
      verifyNullPadding()
      // There will be more bytes after this but as long as we hit $PAD_SIZEx\0 here it's fine

      return DecryptedKVNData(
        uuid = UUID.randomUUID(),
        versionString = VERSION_STRING, // prefer version specified by the reader
        dateCreated = dateCreated,
        dateModified = dateModified,
        category = strCategory,
        nametag = strNametag,
        keyData = keyData,
        decryptedK = keyData.decrypt(bytesEncryptedK).toString(Charsets.UTF_8),
        decryptedV = keyData.decrypt(bytesEncryptedV).toString(Charsets.UTF_8)
      )
    }

    fun writeToDisk(
        contents: DecryptedKVNData,
        writer: BufferedOutputStream) {

      require(contents.keyData != null) { "Key data was not initialized" }
      require(contents.decryptedK != null) { "No key to encrypt" }
      require(contents.decryptedV != null) { "No value to encrypt" }

      // This should intuitively be pre-known but dynamically calculate anyway
      val serializedKeyBytes: ByteArray = contents.keyData!!.serializeToBytes()

      // Go ahead and encrypt K and V here
      val encryptedK: ByteArray = contents.keyData!!.encrypt(contents.decryptedK!!.toByteArray(Charsets.UTF_8))
      val encryptedV: ByteArray = contents.keyData!!.encrypt(contents.decryptedV!!.toByteArray(Charsets.UTF_8))

      // Lengths (ints) as specified in the header spec
      val lenCategory: Int = contents.category.length
      val lenNametag: Int = contents.nametag.length
      val lenKeyBytes: Int = serializedKeyBytes.size
      val lenEK: Int = encryptedK.size
      val lenEV: Int = encryptedV.size

      // Calc lengths of both sections
      val lenHeader = KVNHeader.KVNFILE_SIZE_HEADER_MAGIC +
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

      val intBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
      val writeInt: ((Int) -> Unit) = fun(z) {
        intBuf.rewind()
        writer.write(intBuf.apply { putInt(z) }.array())
      }

      val longBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
      val writeLong: ((Long) -> Unit) = fun(l) {
        longBuf.rewind()
        writer.write(longBuf.apply { putLong(l) }.array())
      }

      val writePadding: (() -> Unit) = fun() {
        writer.write(bytesPadding)
      }

      // Header content
      writer.write(KVNHeader.KVNFILE_HEADER_MAGIC)
      writer.write(VERSION_BYTES)
      writeLong(contents.dateCreated.toEpochMilliseconds())
      writeLong(contents.dateModified!!.toEpochMilliseconds())
      writer.write(ByteArray(24)) // Reserved
      writeInt(lenCategory)
      writeInt(lenNametag)
      writeInt(lenKeyBytes)
      writeInt(lenEK)
      writeInt(lenEV)
      writer.write(ByteArray(52)) // Reserved
      writer.write(bytesPadding)

      // Body content
      writer.write(contents.category.toByteArray(Charsets.UTF_8))
      writePadding()
      writer.write(contents.nametag.toByteArray(Charsets.UTF_8))
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