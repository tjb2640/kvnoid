package tech.fouronesoft.kvnoid.util

import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Central place to store serialization helpers and standard charset/byte order vals
 *
 * @property STANDARD_CHARSET
 * @property STANDARD_BYTE_ORDER
 */
class DataSerializationUtils {
  companion object {
    val STANDARD_CHARSET: Charset = Charsets.UTF_8
    val STANDARD_BYTE_ORDER: ByteOrder = ByteOrder.LITTLE_ENDIAN

    /**
     * Convert a little-endian byte array to an integer value at face-value.
     *
     * @param array ByteArray(4) to convert
     * @return Int primitive
     * @throws RuntimeException if given array is not of size 4
     * @see byteArrayToLongLE
     */
    fun byteArrayToIntLE(array: ByteArray): Int {
      require(array.size == 4) { "4 bytes required here" }
      return array.foldIndexed(0) {
        i, acc, byte -> acc or (byte.toInt() and 0xFF shl (i * 8))
      }
    }

    /**
     * Convert a little-endian byte array to an integer value at face-value.
     *
     * @param array ByteArray(8) to convert
     * @return Long primitive
     * @throws RuntimeException if given array is not of size 8
     * @see byteArrayToIntLE
     */
    fun byteArrayToLongLE(array: ByteArray): Long {
      require(array.size == 8) { "8 bytes required here" }
      return array.foldIndexed(0L) {
          i, acc, byte -> acc or (byte.toLong() and 0xFF shl (i * 8))
      }
    }

    /**
     * Convert an array of bytes into a UTF-8 string.
     *
     * @param array ByteArray of variable length to convert
     * @return UTF-8 String
     * @see stringToUTF8ByteArray
     */
    fun byteArrayToUTF8StringLE(array: ByteArray): String {
      return array.toString(STANDARD_CHARSET)
    }

    /**
     * Convert a String into an array of UTF-8 bytes.
     *
     * @param string UTF-8 String to explode into an array
     * @return UTF-8 ByteArray
     * @see byteArrayToUTF8StringLE
     */
    fun stringToUTF8ByteArray(string: String): ByteArray {
      return string.toByteArray(STANDARD_CHARSET)
    }
  }
}