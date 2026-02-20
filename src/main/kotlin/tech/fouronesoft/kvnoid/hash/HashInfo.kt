package tech.fouronesoft.kvnoid.hash

import org.kotlincrypto.hash.sha3.KeccakDigest
import org.kotlincrypto.hash.sha3.SHA3_256
import org.kotlincrypto.hash.sha3.SHA3_512

class HashInfo(
  val id: String,
  val outputLengthBytes: Int,
  val getProvider: () -> KeccakDigest
) {

  companion object {
    val hashes = mapOf(
      "SHA3-256" to HashInfo(
        id = "SHA3-256",
        outputLengthBytes = 32,
        getProvider = fun(): KeccakDigest {return SHA3_256() },
      ),
      "SHA3-512" to HashInfo(
        id = "SHA3-512",
        outputLengthBytes = 64,
        getProvider = fun(): KeccakDigest {return SHA3_512() },
      )
    )
  }

}