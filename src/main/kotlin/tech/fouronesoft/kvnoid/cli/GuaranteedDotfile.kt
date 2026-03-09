package tech.fouronesoft.kvnoid.cli

import tech.fouronesoft.kvnoid.POSIX_FILEMOD_700
import tech.fouronesoft.kvnoid.util.DataSerializationUtils
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.setPosixFilePermissions

/**
 * Default `$HOME/.config/kvnoid`
 */
val DOTFILE_LOCATION: Path = Paths.get(System.getProperty("user.home"), ".config", "kvnoid")

/**
 * Represents a dotfile saved on the disk and provides a simple `name`/`value` property scheme.
 * Use the companion fun `readMaybeCreate()` to load a dotfile:
 * - The `name` property passed to this function will be the name of the file in the `DOTFILE_LOCATION` dir.
 * - Its file is guaranteed to exist once this fun returns, barring filesystem permission issues.
 * - The instance's `value` can be mutated. After changing the `value`, call `saveValueToDisk()` to disk it.
 */
class GuaranteedDotfile private constructor (
  val name: String,
  var value: String
) {

  init {
    // Automatically guarantee this dotfile's value is stored on the disk
    if (!dotfileWithNameExists(name)) saveValueToDisk()
  }

  /**
   * Saves the instance's value property to the filesystem.
   */
  fun saveValueToDisk() {
    ensureDotfileDirExists()
    Paths.get(DOTFILE_LOCATION.absolutePathString(), name)
      .toFile()
      .writeText(value, DataSerializationUtils.STANDARD_CHARSET)
  }

  companion object {
    /**
     * Makes sure the .dotfile directory `~/.config/kvnoid` or whatever exists
     */
    fun ensureDotfileDirExists() {
      DOTFILE_LOCATION.takeIf { it.isDirectory() } ?: DOTFILE_LOCATION.createDirectories().also {
        it.setPosixFilePermissions(POSIX_FILEMOD_700)
      }
    }

    /**
     * Does a dotfile with the given `name` property exist in `DOTFILE_LOCATION`?
     *
     * @param name String filename
     */
    fun dotfileWithNameExists(name: String): Boolean {
      ensureDotfileDirExists()
      return Paths.get(DOTFILE_LOCATION.absolutePathString(), name).exists()
    }

    /**
     * Create a new "guaranteed" dotfile with the given name and default value.
     *
     * If the file doesn't exist on the disk, the default value will be written to it.
     */
    fun readMaybeCreate(name: String, defaultValue: String = ""): GuaranteedDotfile {
      ensureDotfileDirExists()
      return GuaranteedDotfile(
        name = name,
        value = defaultValue.takeIf { !dotfileWithNameExists(name) } ?: Paths.get(DOTFILE_LOCATION.absolutePathString(), name)
          .toFile()
          .readText(DataSerializationUtils.STANDARD_CHARSET))
    }
  }
}