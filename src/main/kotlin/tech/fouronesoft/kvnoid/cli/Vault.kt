package tech.fouronesoft.kvnoid.cli

import tech.fouronesoft.kvnoid.POSIX_FILEMOD_700
import tech.fouronesoft.kvnoid.file.KVNFileData
import tech.fouronesoft.kvnoid.file.KVNFileMetadata
import tech.fouronesoft.kvnoid.util.ObfuscatedString
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.setPosixFilePermissions

/**
 * Represents an index of .kvn file entries in a single directory on the disk.
 *
 * The Vault handles finding and reading entries, creating new ones, and soft-deleting old ones.
 *
 * All entries are indexed using their UUID.
 */
class Vault(val vaultPath: Path, val vaultKey: ObfuscatedString) {

  /* metadata.uuid -> KVNFileMetadata */
  private val uuidToMetadata: MutableMap<UUID, KVNFileMetadata> = mutableMapOf()

  /* metadata.uuid -> Path */
  private val uuidToFilepath: MutableMap<UUID, Path> = mutableMapOf()

  /**
   * Returns an immutable copy of the uuidToMetadata map.
   */
  fun getEntries(): HashMap<UUID, KVNFileMetadata> {
    return HashMap(uuidToMetadata)
  }

  /**
   * Index metadata from the file at this path, return Boolean success
   */
  private fun indexEntryAt(path: Path): Boolean {
    val metadata = KVNFileMetadata.readFromAbsolutePath(path.absolutePathString()) ?: return false
    uuidToMetadata[metadata.uuid] = metadata
    uuidToFilepath[metadata.uuid] = path.toAbsolutePath()
    return true
  }

  /**
   * Rebuild all index maps. Run after any file is mutated.
   */
  private fun reindex() {
    uuidToMetadata.clear()
    uuidToFilepath.clear()
    val files = vaultPath.toFile().listFiles()?.filter { f -> f.path.lowercase().endsWith(".kvn") }
    files?.forEach { f -> indexEntryAt(f.toPath()) }
  }

  /**
   * Returns the filepath for a theoretical entry with a specific UUID
   */
  fun getPathFor(uuid: UUID): Path {
    return Paths.get(vaultPath.absolutePathString(), "${uuid}.kvn")
  }

  /**
   * Returns the soft-deleted filepath for a theoretical entry with a specific UUID
   */
  fun getDeletePathFor(uuid: UUID): Path {
    return Paths.get(vaultPath.absolutePathString(), "${uuid}.kvn.deleted")
  }

  /**
   * Creates a new entry, writing it to the disk and indexing it in the vault.
   */
  fun createEntry(kvnFileData: KVNFileData) {
    val filePath = getPathFor(kvnFileData.metadata.uuid)
    vaultKey.getProvider().use { provider ->
      kvnFileData.writeToDisk(
        path = filePath, passphrase = provider.get()
      )
    }
    indexEntryAt(filePath)
  }

  /**
   * Attempt to read an entry from the disk with the given UUID
   */
  fun readEntryWithUUID(uuid: UUID): KVNFileData? {
    takeIf { uuidToMetadata.containsKey(uuid) } ?: return null
    val metadata: KVNFileMetadata = uuidToMetadata[uuid]!!

    vaultKey.getProvider().use { provider ->
      return KVNFileData.readFromAbsolutePath(
        path = getPathFor(uuid), metadata = metadata, passphrase = provider.get()
      )
    }
  }

  /**
   * Moves an entry from file.kvn to file.kvn.deleted on the disk.
   */
  fun softDeleteEntry(uuid: UUID?): Boolean {
    takeIf { uuidToMetadata.containsKey(uuid) } ?: return false
    val origin: Path = getPathFor(uuid!!)
    if (origin.exists()) origin.toFile().renameTo(getDeletePathFor(uuid).toFile())
    reindex()
    return true
  }

  init {
    vaultPath.takeIf { it.isDirectory() } ?: vaultPath.createDirectories().also {
      it.setPosixFilePermissions(POSIX_FILEMOD_700)
    }
    reindex()
  }
}