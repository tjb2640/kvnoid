package tech.fouronesoft.kvnoid.encryption

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * javax crypto wrapper using AES-256-GCM with a passphrase to generate or load keys.
 */
class AESGCMKey(val secretKey: SecretKeySpec, val salt: ByteArray, val iv: ByteArray, val aad: ByteArray) {

  companion object {
    const val TAG_SIZE_BITS = 128
    const val KEY_SIZE_BITS = 256
    const val SALT_SIZE_BITS = KEY_SIZE_BITS
    const val IV_SIZE_BITS = 12 * 8
    const val ALGO_XFORM_STRING = "AES/GCM/NoPadding"

    fun fromKnownKeyData(passphrase: String, salt: ByteArray, iv: ByteArray, aad: ByteArray): AESGCMKey {
      val spec = PBEKeySpec(passphrase.toCharArray(), salt, 65536, KEY_SIZE_BITS)
      val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
      val keyBytes = factory.generateSecret(spec).encoded

      return AESGCMKey(
        secretKey = SecretKeySpec(keyBytes, "AES"),
        salt = salt,
        iv = iv,
        aad = aad
      )
    }

    fun fromNewPlaintextPassphrase(passphrase: String): AESGCMKey {
      val secureRandom: SecureRandom = SecureRandom.getInstanceStrong()
      val salt = ByteArray(SALT_SIZE_BITS / 8).apply { secureRandom.nextBytes(this) }
      val iv = ByteArray(IV_SIZE_BITS / 8).apply { secureRandom.nextBytes(this) }
      val aad = ByteArray(TAG_SIZE_BITS / 8).apply { secureRandom.nextBytes(this) }
      return fromKnownKeyData(passphrase, salt, iv, aad)
    }

    /**
     * Load a key into memory from a KVN file's serialized key bytes.
     * This assumes that the bytes are organized according to spec
     * TODO ^
     *
     * @param passphrase: key passphrase to initialize with
     * @param bytes: key data in packed bytes - should be salt, AAD tag, then IV
     */
    fun fromSerializedBytes(passphrase: String, bytes: ByteArray): AESGCMKey {
      val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      val salt = ByteArray(SALT_SIZE_BITS / 8)
      val aad = ByteArray(TAG_SIZE_BITS / 8)
      val iv = ByteArray(IV_SIZE_BITS / 8)
      buf.get(salt)
      buf.get(iv)
      buf.get(aad)
      return fromKnownKeyData(passphrase, salt, iv, aad)
    }
  }

  /**
   * Encrypt plaintext using this key
   *
   * @param plaintext: to encrypt
   */
  fun encrypt(plaintext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(ALGO_XFORM_STRING)
    cipher.init(
      Cipher.ENCRYPT_MODE,
      this.secretKey,
      GCMParameterSpec(TAG_SIZE_BITS, this.iv))
    cipher.updateAAD(this.aad)
    return cipher.doFinal(plaintext)
  }

  /**
   * Decrypt ciphertext encrypted using this key
   *
   * @param ciphertext: to decrypt
   */
  fun decrypt(ciphertext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(ALGO_XFORM_STRING)
    cipher.init(
      Cipher.DECRYPT_MODE,
      this.secretKey,
      GCMParameterSpec(TAG_SIZE_BITS, this.iv))
    cipher.updateAAD(this.aad)
    return cipher.doFinal(ciphertext)
  }

  fun serializeToBytes(): ByteArray {
    val buf = ByteBuffer.allocate((SALT_SIZE_BITS + IV_SIZE_BITS + TAG_SIZE_BITS) / 8).order(ByteOrder.LITTLE_ENDIAN)
    buf.put(this.salt).put(this.iv).put(this.aad)
    return buf.array()
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as AESGCMKey

    if (secretKey != other.secretKey) return false
    if (!iv.contentEquals(other.iv)) return false
    if (!salt.contentEquals(other.salt)) return false
    if (!aad.contentEquals(other.aad)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = secretKey.hashCode()
    result = 31 * result + iv.contentHashCode()
    result = 31 * result + salt.contentHashCode()
    result = 31 * result + aad.contentHashCode()
    return result
  }

}
