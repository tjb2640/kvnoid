package tech.fouronesoft.kvnoid.encryption

import tech.fouronesoft.kvnoid.util.DataSerializationUtils
import tech.fouronesoft.kvnoid.util.DataSerializationUtils.Companion.STANDARD_CHARSET
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
 * Locked to AES-256-GCM.
 *
 * Right now I am not doing anything special with the AAD. TODO
 */
class AESGCMKey(val secretKey: SecretKeySpec, val iv: ByteArray, val salt: ByteArray, val aad: ByteArray) {

  companion object {
    const val AAD_SIZE_BITS = 128
    const val KEY_SIZE_BITS = 256
    const val SALT_SIZE_BITS = KEY_SIZE_BITS
    const val IV_SIZE_BITS = 12 * 8
    const val ALGO_XFORM_STRING = "AES/GCM/NoPadding"

    /**
     * Builds a usable AES key given pre-known GCM key data.
     *
     * @param passphrase used to generate the key
     * @param iv of size IV_SIZE_BITS / 8
     * @param salt of size SALT_SIZE_BITS / 8
     * @param aad of size AAD_SIZE_BITS / 8
     */
    fun fromKnownKeyData(passphrase: String, iv: ByteArray, salt: ByteArray, aad: ByteArray): AESGCMKey {
      val spec = PBEKeySpec(passphrase.toCharArray(), salt, 65536, KEY_SIZE_BITS)
      val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
      val keyBytes = factory.generateSecret(spec).encoded

      return AESGCMKey(
        secretKey = SecretKeySpec(keyBytes, "AES"),
        iv = iv,
        salt = salt,
        aad = aad
      )
    }

    /**
     * Generates a new key given an already-known passphrase.
     *
     * @param passphrase used to generate the key
     */
    fun fromNewPlaintextPassphrase(passphrase: String): AESGCMKey {
      val secureRandom: SecureRandom = SecureRandom.getInstanceStrong()
      val iv = ByteArray(IV_SIZE_BITS / 8).apply { secureRandom.nextBytes(this) }
      val salt = ByteArray(SALT_SIZE_BITS / 8).apply { secureRandom.nextBytes(this) }
      val aad = ByteArray(AAD_SIZE_BITS / 8).apply { secureRandom.nextBytes(this) }
      return fromKnownKeyData(passphrase, iv, salt, aad)
    }

    /**
     * Load a key into memory from a KVN file's serialized key bytes.
     * This assumes that the bytes are organized according to spec
     * TODO ^
     *
     * @param passphrase: key passphrase to initialize with
     * @param bytes: key data in packed bytes - should be IV, salt, then AAD
     */
    fun fromSerializedBytes(passphrase: String, bytes: ByteArray): AESGCMKey {
      val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      val iv = ByteArray(IV_SIZE_BITS / 8)
      val salt = ByteArray(SALT_SIZE_BITS / 8)
      val aad = ByteArray(AAD_SIZE_BITS / 8)
      buf.get(iv)
      buf.get(salt)
      buf.get(aad)
      return fromKnownKeyData(passphrase, iv, salt, aad)
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
      GCMParameterSpec(AAD_SIZE_BITS, this.iv))
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
      GCMParameterSpec(AAD_SIZE_BITS, this.iv))
    cipher.updateAAD(this.aad)
    return cipher.doFinal(ciphertext)
  }

  fun decryptToUTF8String(ciphertext: ByteArray): String {
    return decrypt(ciphertext).toString(STANDARD_CHARSET)
  }

  fun serializeToBytes(): ByteArray {
    val buf = ByteBuffer.allocate((SALT_SIZE_BITS + IV_SIZE_BITS + AAD_SIZE_BITS) / 8).order(DataSerializationUtils.STANDARD_BYTE_ORDER)
    buf.put(this.iv).put(this.salt).put(this.aad)
    return buf.array()
  }

}
