package tech.fouronesoft.kvnoid.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals

class ObfuscatedStringTest {

  @Test
  fun test_noArgsFedToConstructor() {
    val obfuscatedString = ObfuscatedString()
    val provider: ObfuscatedString.ValueProvider = obfuscatedString.getProvider()
    assertContentEquals(
      CharArray(1),
      provider.get())
    provider.close()
    assertThrows<RuntimeException>("This provider has been closed and its value can no longer be accessed") {
      provider.get()
    }
  }

  @Test
  fun test_sameValueProvided() {
    val obfuscatedString = ObfuscatedString(
      initialValue = "abcde".toByteArray(DataSerializationUtils.STANDARD_CHARSET))

    obfuscatedString.getProvider().use { provider ->
      assertContentEquals("abcde".toCharArray(), provider.get())
      provider.close()
    }
  }

  @Test
  fun test_constructor_overwritesSourceArray() {
    val initialArray: ByteArray = "abcde".toByteArray(Charsets.UTF_8)
    ObfuscatedString(initialValue = initialArray, overwriteInitialValueSource = true)
    assertContentEquals(ByteArray(5), initialArray)
  }

  @Test
  fun test_constructor_preservesSourceArray() {
    val initialArray: ByteArray = "abcde".toByteArray(Charsets.UTF_8)
    ObfuscatedString(initialValue = initialArray)
    assertContentEquals("abcde".toByteArray(Charsets.UTF_8), initialArray)
  }

  @Test
  fun test_setValue_overwritesSourceArray() {
    val obfuscatedString = ObfuscatedString()
    val initialArray: ByteArray = "abcde".toByteArray(Charsets.UTF_8)
    obfuscatedString.setValue(newValue = initialArray, overwrite = true)

    assertContentEquals(ByteArray(5), initialArray)
    obfuscatedString.getProvider().use { provider ->
      assertContentEquals("abcde".toCharArray(), provider.get())
    }
  }

  @Test
  fun test_setValue_preservesSourceArray() {
    val obfuscatedString = ObfuscatedString()
    val initialArray: ByteArray = "abcde".toByteArray(Charsets.UTF_8)
    obfuscatedString.setValue(newValue = initialArray)
    assertContentEquals("abcde".toByteArray(Charsets.UTF_8), initialArray)
  }

}