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
import kotlin.io.path.isDirectory
import kotlin.io.path.setPosixFilePermissions

class Vault (val vaultPath: Path, val vaultKey: ObfuscatedString) {

  private val uuidToMetadata: MutableMap<UUID, KVNFileMetadata> = mutableMapOf()
  private val filepathToUUID: MutableMap<Path, UUID> = mutableMapOf()
  private val uuidToFilepath: MutableMap<UUID, Path> = mutableMapOf()

  fun getEntries(): HashMap<UUID, KVNFileMetadata> {
    return HashMap(uuidToMetadata)
  }

  fun getByUUID(uuid: UUID): KVNFileMetadata? {
    return uuidToMetadata[uuid]
  }

  /**
   * Index metadata from the file at this path, return Boolean success
   */
  fun indexFile(path: Path): Boolean {
    val metadata = KVNFileMetadata.readFromAbsolutePath(path.absolutePathString()) ?: return false
    uuidToMetadata[metadata.uuid] = metadata
    uuidToFilepath[metadata.uuid] = path.toAbsolutePath()
    filepathToUUID[path.toAbsolutePath()] = metadata.uuid
    return true
  }

  fun reindex() {
    val files = vaultPath.toFile().listFiles()?.filter { f -> f.path.lowercase().endsWith(".kvn") }
    files?.forEach { f -> indexFile(f.toPath()) }
  }

  fun getPathFor(kvnFileData: KVNFileData): Path {
    return Paths.get(vaultPath.absolutePathString(), "${kvnFileData.metadata.uuid}.kvn")
  }

  fun createFile(kvnFileData: KVNFileData) {
    val filePath = getPathFor(kvnFileData)
    vaultKey.getProvider().use { provider ->
      kvnFileData.writeToDisk(
        path = filePath,
        passphrase = provider.get())
    }
    indexFile(filePath)
  }

  fun openFile(uuid: UUID): KVNFileData? {
    takeIf { uuidToMetadata.containsKey(uuid) } ?: return null
    val metadata: KVNFileMetadata = uuidToMetadata[uuid]!!
    val filepath: Path = uuidToFilepath[uuid]!!

    vaultKey.getProvider().use { provider ->
      return KVNFileData.readFromAbsolutePath(
        path = filepath,
        metadata = metadata,
        passphrase = provider.get()
      )
    }
  }

  fun printUUIDS() {
    println("In vault now:")
    uuidToMetadata.forEach { (uuid, _) -> println(" $uuid") }
  }

  init {
    vaultPath.takeIf { it.isDirectory() } ?: vaultPath.createDirectories().also {
      it.setPosixFilePermissions(POSIX_FILEMOD_700)
    }
    reindex()
  }
}