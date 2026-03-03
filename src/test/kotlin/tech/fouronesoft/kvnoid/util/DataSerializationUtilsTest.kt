package tech.fouronesoft.kvnoid.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteOrder
import kotlin.test.assertEquals

class DataSerializationUtilsTest {

  @Test
  fun test_charsetLockedToUTF8() {
    assertEquals(
      Charsets.UTF_8,
      DataSerializationUtils.STANDARD_CHARSET
    )
  }

  @Test
  fun test_byteOrderLockedToLittleEndian() {
    assertEquals(
      ByteOrder.LITTLE_ENDIAN,
      DataSerializationUtils.STANDARD_BYTE_ORDER
    )
  }

  @Test
  fun test_byteArrayToIntLE_failOnWeirdInputSize() {
    assertThrows<RuntimeException>("Should throw with a non-4-sized array") {
      DataSerializationUtils.byteArrayToIntLE(ByteArray(3))
    }

    assertThrows<RuntimeException>("Should throw with a non-4-sized array") {
      DataSerializationUtils.byteArrayToIntLE(ByteArray(5))
    }
  }

  @Test
  fun test_byteArrayToIntLE_valueCorrectness() {
    assertEquals(0, DataSerializationUtils.byteArrayToIntLE(ByteArray(4)))
    assertEquals(
      Integer.MAX_VALUE,
      DataSerializationUtils.byteArrayToIntLE(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F.toByte()))
    )
    assertEquals(
      Integer.MIN_VALUE,
      DataSerializationUtils.byteArrayToIntLE(byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x80.toByte()))
    )
    assertEquals(
      16,
      DataSerializationUtils.byteArrayToIntLE(byteArrayOf(0x10.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()))
    )
    assertEquals(
      -1,
      DataSerializationUtils.byteArrayToIntLE(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
    )
  }

  @Test
  fun test_byteArrayToLongLE_failOnWeirdInputSize() {
    assertThrows<RuntimeException>("Should throw with a non-8-sized array") {
      DataSerializationUtils.byteArrayToLongLE(ByteArray(7))
    }

    assertThrows<RuntimeException>("Should throw with a non-8-sized array") {
      DataSerializationUtils.byteArrayToLongLE(ByteArray(9))
    }

    assertEquals(0, DataSerializationUtils.byteArrayToLongLE(ByteArray(8)))
  }

  @Test
  fun test_byteArrayToLongLE_valueCorrectness() {
    assertEquals(0L, DataSerializationUtils.byteArrayToLongLE(ByteArray(8)))
    assertEquals(
      Long.MAX_VALUE,
      DataSerializationUtils.byteArrayToLongLE(byteArrayOf(
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F.toByte()
      ))
    )
    assertEquals(
      Long.MIN_VALUE,
      DataSerializationUtils.byteArrayToLongLE(byteArrayOf(
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x80.toByte()
      ))
    )
    assertEquals(
      16L,
      DataSerializationUtils.byteArrayToLongLE(byteArrayOf(
        0x10.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
      ))
    )
    assertEquals(
      -1L,
      DataSerializationUtils.byteArrayToLongLE(byteArrayOf(
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
      ))
    )
  }


}