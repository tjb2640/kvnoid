package tech.fouronesoft.kvnoid.util

import tech.fouronesoft.kvnoid.encryption.AESGCMKey

const val ZERO_BYTE: Byte = 0.toByte()

/**
 * A way to store String data in memory in an obfuscated way
 */
class ObfuscatedString(
  initialValue: ByteArray = ByteArray(1), overwriteInitialValueSource: Boolean = false
) {

  private val encryptionKey: AESGCMKey = AESGCMKey.temporaryKey(size = 128)
  private var value: ByteArray = ByteArray(0)
  private var length: Int = 0

  init {
    setValue(newValue = initialValue, overwrite = overwriteInitialValueSource)
  }

  /**
   * Store a new obfuscated value.
   *
   * @param newValue to store
   * @param overwrite if newValue should be zeroed out after consumption (default `false`)
   * @return this ObfuscatedString instance
   */
  fun setValue(newValue: ByteArray, overwrite: Boolean = false): ObfuscatedString {
    this.value = this.encryptionKey.encrypt(newValue)
    this.length = newValue.size
    if (overwrite) for (i in 0..<newValue.size) newValue[i] = ZERO_BYTE
    return this
  }

  /**
   * Get a ValueProvider, an AutoCloseable which temporarily exposes the plain unobfuscated value of
   * the stored string.
   *
   * @return ObfuscatedString.ValueProvider
   */
  fun getProvider(): ValueProvider {
    val decryptedByteArray: ByteArray = this.encryptionKey.decrypt(this.value)
    val charArray = CharArray(decryptedByteArray.size)

    for (i in 0..<decryptedByteArray.size) {
      charArray[i] = decryptedByteArray[i].toInt().toChar()
      decryptedByteArray[i] = ZERO_BYTE
    }

    return ValueProvider(charArray)
  }

  /**
   * Returns the cached length of the original byte array
   */
  fun getLength(): Int {
    return this.length
  }

  /**
   * Temporarily exposes the plain unobfuscated value of an obfuscated string.
   * When it is close()d it will zero out the value it has stored in memory.
   *
   * @property get retrieve the value
   * @property close zero out the value in memory
   */
  class ValueProvider(private val value: CharArray) : AutoCloseable {
    private var closed: Boolean = false

    /**
     * Retrieves the unobfuscated value
     *
     * @throws RuntimeException if this provider is closed
     */
    fun get(): CharArray {
      require(!this.closed) { "This provider has been closed and its value can no longer be accessed" }
      return this.value
    }

    /**
     * Zeroes out the unobfuscated value and renders the instance useless
     */
    override fun close() {
      for (i in 0..<value.size) value[i] = Char(0)
      this.closed = true
    }
  }
}