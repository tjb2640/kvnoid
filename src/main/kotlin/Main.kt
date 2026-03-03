import tech.fouronesoft.kvnoid.util.DataSerializationUtils
import tech.fouronesoft.kvnoid.util.ObfuscatedString
import java.io.File
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.Path
import kotlin.io.path.setPosixFilePermissions
import kotlin.time.Clock

// Very simple Kotlin script for interacting with the read/write system

// Dotfile management
val DOTFILE_PARENT: File? = Paths.get(System.getenv("HOME"), ".config", "kvnoid").toFile()
val CONFIGS: Map<String, File> = mapOf(
  "vault_location" to File(DOTFILE_PARENT!!, "vault_location"),
  "last_access" to File(DOTFILE_PARENT, "last_access")
)

fun readDotfile(name: String): String {
  return CONFIGS[name]!!.readText(DataSerializationUtils.STANDARD_CHARSET).trim()
}

fun writeDotfile(name: String, content: String) {
  CONFIGS[name]!!.writeText(content.trim(), DataSerializationUtils.STANDARD_CHARSET)
}

fun makeDotfiles() {
  DOTFILE_PARENT!!.mkdirs()
  val ownerOnly: Set<PosixFilePermission> = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE
  )
  for (f in CONFIGS.values) {
    if (!f.exists()) f.createNewFile()
    f.toPath().setPosixFilePermissions(ownerOnly)
  }
}

fun promptAndCachePassword(): ObfuscatedString {
  print("(Enter vault password) : ")

  val inputBytes = ByteArray(8192)
  var i = 0

  while (true) {
    val bin: Int = System.`in`.read()
    if (0xa == bin || 0xd == bin || i == inputBytes.size) {
      if (i == 0) {
        println("\n-> Enter password and hit enter\n")
        continue
      }
      ObfuscatedString(
        initialValue = inputBytes.sliceArray(0.rangeUntil(i)),
        overwriteInitialValueSource = true
      ).also {
          0.rangeUntil(i).forEach { used -> inputBytes[used] = 0 }
          return it
      }
    }
    inputBytes[i] = bin.toByte()
    i++
  }
}

fun promptPlainValue(longText: String, smallText: String): String {
  while (true) {
    println(longText)
    print("($smallText): ")
    readln().also {
      if (it.isBlank()) continue
      return it
    }
  }
}

fun markLastAccess() {
  writeDotfile("last_access", Clock.System.now().toString())
}

fun main() {
  println("== kvnoid ==")
  val storedKeyStr: ObfuscatedString = promptAndCachePassword()

  // Resolve dotfiles
  makeDotfiles()
  if (readDotfile("vault_location").isBlank()) {
    promptPlainValue(
      longText = "Please enter a location for the vault.",
      smallText = "/path/to/vault"
    ).also { writeDotfile("vault_location", it) }
  }
  markLastAccess()

  // Load vault
  val kvnFiles: MutableList<File> = mutableListOf()
  Path(readDotfile("vault_location")).also {
    val vaultDir: File = it.toFile()
    if (!vaultDir.isDirectory) vaultDir.mkdirs()
    kvnFiles.addAll(vaultDir.listFiles().takeWhile { f -> f.path.lowercase().endsWith(".kvn") })
    // TODO: continue
  }
}