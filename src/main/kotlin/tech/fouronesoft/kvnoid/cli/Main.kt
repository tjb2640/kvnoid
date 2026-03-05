package tech.fouronesoft.kvnoid.cli

import tech.fouronesoft.kvnoid.file.KVNFileData
import tech.fouronesoft.kvnoid.file.KVNFileMetadata
import tech.fouronesoft.kvnoid.util.DataSerializationUtils
import tech.fouronesoft.kvnoid.util.ObfuscatedString
import java.io.File
import java.lang.System.console
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.setPosixFilePermissions
import kotlin.time.Clock

// Very simple Kotlin script for interacting with the read/write system

// Dotfile management

val DOTFILE_PARENT: File? = Paths.get(System.getenv("HOME"), ".config", "kvnoid").toFile()
val CONFIGS: Map<String, File> = mapOf(
  "vault_location" to File(DOTFILE_PARENT!!, "vault_location"),
  "last_access" to File(DOTFILE_PARENT, "last_access")
)

val ownerOnlyPOSIXPermissions: Set<PosixFilePermission> = setOf(
  PosixFilePermission.OWNER_READ,
  PosixFilePermission.OWNER_WRITE,
  PosixFilePermission.OWNER_EXECUTE
)

fun readDotfile(name: String): String {
  return CONFIGS[name]!!.readText(DataSerializationUtils.STANDARD_CHARSET).trim()
}

fun writeDotfile(name: String, content: String) {
  CONFIGS[name]!!.writeText(content.trim(), DataSerializationUtils.STANDARD_CHARSET)
}

fun markLastAccess() {
  writeDotfile("last_access", Clock.System.now().toString())
}

fun makeDotfiles() {
  DOTFILE_PARENT!!.mkdirs()
  for (f in CONFIGS.values) {
    if (!f.exists()) f.createNewFile()
    f.toPath().setPosixFilePermissions(ownerOnlyPOSIXPermissions)
  }
}

// Prompt helpers

fun promptAndCacheVaultKey(): ObfuscatedString {
  println("Enter vault key to use with this vault.")
  print(" vault key: ")

  while (true) {
    // Console will be null in the IDE
    val inputChars: CharArray = console()?.readPassword() ?: readln().toCharArray()

    if (inputChars.isEmpty()) {
      println("\n !! -> Enter vault key and hit enter\n")
      continue
    }

    val inputBytes = ByteArray(inputChars.size)
    inputChars.forEachIndexed { index, ch ->
      inputBytes[index] = ch.code.toByte()
      inputChars[index] = 0.toChar()
    }

    return ObfuscatedString(
      initialValue = inputBytes,
      overwriteInitialValueSource = true
    )
  }
}

fun promptPlainValue(longText: String, smallText: String, default: String = ""): String {
  while (true) {
    println(longText)
    print(" $smallText (default \"$default\"): ")
    readln().also {
      return it.ifBlank { default }
    }
  }
}

// Entrypoint into this script

class Main {
  companion object {
    @JvmStatic
    fun main(args: Array<String>) {
      println("== kvnoid ==")

      // Resolve dotfiles
      makeDotfiles()
      val defaultVaultLocation: String = readDotfile("vault_location").ifBlank {
        promptPlainValue(
          longText = "Looks like first-time setup. Please enter a default vault location.",
          smallText = "/path/to/vault"
        ).also {
          writeDotfile("vault_location", it)
          println(" -> First-time setup is done.")
        }
      }
      markLastAccess()

      val selectedVaultLocation = promptPlainValue(
        longText = "Enter vault location to load the vault.",
        smallText = "path to vault",
        default = defaultVaultLocation
      )
      println(" -> Selected vault at '$selectedVaultLocation'")

      val storedKeyStr: ObfuscatedString = promptAndCacheVaultKey()

      // Load vault
      val kvnFiles: MutableList<File> = mutableListOf()
      Path(selectedVaultLocation).also {
        val vaultDir: File = it.toFile()
        if (!vaultDir.isDirectory) vaultDir.mkdirs()

        it.setPosixFilePermissions(ownerOnlyPOSIXPermissions)
        kvnFiles.addAll(vaultDir.listFiles().takeWhile { f -> f.path.lowercase().endsWith(".kvn") })
        for (foundFile in kvnFiles) {
          KVNFileMetadata.readFromAbsolutePath(foundFile.absolutePath)!!.also { meta ->
            println("${meta.uuid}: " +
                "${DataSerializationUtils.byteArrayToUTF8StringLE(meta.nametag)} - " +
                "Created ${meta.dateCreated}")
          }
        }

        // TODO
        val diskyData = KVNFileData(
          metadata = KVNFileMetadata(
            category = "test category".toByteArray(DataSerializationUtils.STANDARD_CHARSET),
            nametag = "test nametag".toByteArray(DataSerializationUtils.STANDARD_CHARSET),
          ),
          decryptedV = "test corpus ab".toByteArray(DataSerializationUtils.STANDARD_CHARSET)
        )

        storedKeyStr.getProvider().use { vaultKeyProvider ->
          val fpath: String = Path(selectedVaultLocation, "${diskyData.metadata.uuid}.kvn").absolutePathString()
          diskyData.writeToDisk(
            fpath,
            String(vaultKeyProvider.get()))
          val readback = KVNFileData.readFromAbsolutePath(
            fpath,
            KVNFileMetadata.readFromAbsolutePath(fpath)!!,
            vaultKeyProvider.get()
          )!!
          println("Decrypted v is ${DataSerializationUtils.byteArrayToUTF8StringLE(readback.decryptedV)}")
        }
      }
    }
  }

}


