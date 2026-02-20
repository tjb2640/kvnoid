package tech.fouronesoft.kvnoid.file.spec.pieces

import java.nio.ByteBuffer


class KVNHeader {
  companion object {
    val KVNFILE_HEADER_MAGIC: ByteArray = byteArrayOf(
      'K'.code.toByte(), 'V'.code.toByte(), 'N'.code.toByte(), 'F'.code.toByte(),
      0x00, 0x00, 0x00
    )

    const val KVNFILE_SIZE_HEADER_MAGIC: Int = 7
    const val KVNFILE_SIZE_HEADER_VERSION: Int = 5

    fun versionBytesToString(versionBytes: ByteArray): String {
      require (versionBytes.size == KVNFILE_SIZE_HEADER_VERSION) { "Version bytes must be 5 long" }
      val century = versionBytes[0].toString().padStart(2, '0')
      val year = versionBytes[1].toString().padStart(2, '0')
      val month = versionBytes[2].toString().padStart(2, '0')
      val day = versionBytes[3].toString().padStart(2, '0')
      val revision = versionBytes[4].toHexString().padStart(2, '0')
      return "$century$year$month$day$revision"
    }

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
  }
}