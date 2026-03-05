package tech.fouronesoft.kvnoid.cli.util

import tech.fouronesoft.kvnoid.util.DataSerializationUtils
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.setPosixFilePermissions

val DOTFILE_LOCATION: Path = Paths.get(System.getProperty("user.home"), ".config", "kvnoid")
val POSIX_MOD_700: Set<PosixFilePermission> = setOf(
  PosixFilePermission.OWNER_READ,
  PosixFilePermission.OWNER_WRITE,
  PosixFilePermission.OWNER_EXECUTE // required for directory traversal
)

class Dotfile (
  val name: String,
  var value: String
) {

  init {
    if (!exists(name)) write()
  }

  fun write() {
    ensureDirectory()
    Paths.get(DOTFILE_LOCATION.absolutePathString(), name)
      .toFile()
      .writeText(value, DataSerializationUtils.STANDARD_CHARSET)
  }

  companion object {
    fun ensureDirectory() {
      DOTFILE_LOCATION.takeIf { it.isDirectory() } ?: DOTFILE_LOCATION.createDirectories().also {
        it.setPosixFilePermissions(POSIX_MOD_700)
      }
    }

    fun exists(name: String): Boolean {
      ensureDirectory()
      return Paths.get(DOTFILE_LOCATION.absolutePathString(), name).exists()
    }

    fun read(name: String, defaultValue: String = ""): Dotfile {
      ensureDirectory()
      return Dotfile(
        name = name,
        value = defaultValue.takeIf { !exists(name) } ?: Paths.get(DOTFILE_LOCATION.absolutePathString(), name)
          .toFile()
          .readText(DataSerializationUtils.STANDARD_CHARSET))
    }
  }
}