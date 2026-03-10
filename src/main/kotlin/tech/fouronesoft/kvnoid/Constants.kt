package tech.fouronesoft.kvnoid

import java.nio.file.attribute.PosixFilePermission

val POSIX_FILEMOD_700: Set<PosixFilePermission> = setOf(
  PosixFilePermission.OWNER_READ,
  PosixFilePermission.OWNER_WRITE,
  PosixFilePermission.OWNER_EXECUTE // required for directory traversal
)
