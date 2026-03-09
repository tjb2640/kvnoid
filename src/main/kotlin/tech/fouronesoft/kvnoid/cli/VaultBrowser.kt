package tech.fouronesoft.kvnoid.cli

import tech.fouronesoft.kvnoid.file.KVNFileData
import tech.fouronesoft.kvnoid.file.KVNFileMetadata
import tech.fouronesoft.kvnoid.util.DataSerializationUtils
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil

private var globalEid = 0

class KVNVaultBrowser (val vault: Vault) {

  private data class VaultEntry (
    val metadata: KVNFileMetadata,
    val eid: Int = (++globalEid),
    val displayLine: String = StringBuilder()
      // "EID" is printed in drawList() for state-based colour; the rest of this is pre-calculated here
      .append("${Terminal.wrap(" |", Terminal.GREY)} ")
      .append("${Terminal.wrap(
        DataSerializationUtils.byteArrayToUTF8StringLE(metadata.category), Terminal.GREEN)} ")
      .append(Terminal.wrap("@ ", Terminal.GREY))
      .appendLine("${Terminal.wrap(
        DataSerializationUtils.byteArrayToUTF8StringLE(metadata.nametag), Terminal.BLUE)} ")
      .appendLine(Terminal.wrap("      |  UUID:     ${metadata.uuid}", Terminal.GREY))
      .appendLine(Terminal.wrap("      |  Created:  ${metadata.dateCreated}", Terminal.GREY))
      .append(Terminal.wrap("------|  Modified: ${metadata.dateModified}", Terminal.GREY))
      .toString()
  )

  var page: Int = 1
  var entriesPerPage: Int = 5
  var filterCategory: String = ""
  var filterNametag: String = ""
  var filterUUID: String = ""

  var promptFeedback: String? = "Key initialized."
  private val entries: MutableMap<UUID, VaultEntry> = mutableMapOf()
  private val eidToUUID: MutableMap<Int, UUID> = mutableMapOf()
  private val categoryIndex: MutableSet<String> = mutableSetOf()
  private val badCryptoEid: MutableSet<Int> = mutableSetOf()

  init {
    indexEntries()
  }

  fun indexEntries() {
    globalEid = 0
    vault.getEntries().forEach { (uuid, metadata) ->
      VaultEntry(metadata = metadata).also {
        entries[uuid] = it
        eidToUUID[it.eid] = uuid
        categoryIndex.add(DataSerializationUtils.byteArrayToUTF8StringLE(metadata.category).lowercase())
      }
    }
  }

  fun actionPromptNewEntry() {

  }

  fun actionViewValue(userOptions: List<String>, doNotWindow: Boolean = false) {
    vault.vaultKey.getProvider().use { vaultKeyProvider ->
      val eid = userOptions[1].toIntOrNull()
      if (eid == null) {
        promptFeedback = Terminal.wrap("Entry ID '$eid' not in number format.", Terminal.RED)
        return
      }

      val uuid = eidToUUID[eid]
      if (eidToUUID[eid] == null) {
        promptFeedback = Terminal.wrap("Entry ID '$eid' doesn't exist.", Terminal.RED)
        return
      }

      val metadata = entries[uuid]?.metadata
      if (metadata == null) {
        indexEntries()
        promptFeedback = "Re-indexed entries due to a problem"
        return
      }

      val kvnFileData = KVNFileData.readFromAbsolutePath(
        path = metadata.filePath!!,
        metadata = metadata,
        passphrase = vaultKeyProvider.get()
      )
      if (kvnFileData == null) {
        indexEntries()
        promptFeedback = "File for entry $eid doesn't exist anymore, re-indexed entries."
        return
      }

      if (kvnFileData.decryptedV!!.getLength() == 0) {
        badCryptoEid.add(eid)
        promptFeedback = Terminal.wrap("Could not decrypt entry. Wrong vault key?", Terminal.BLUE)
      } else {
        KVNFileDisplayer(kvnFileData).displayValueOnScreen(0.takeIf { doNotWindow } ?: 9)
      }
    }
  }

  fun drawOnLoop() {
    fun drawHeader() {
      print(StringBuilder()
        .append(Terminal.wrap("KVNoid ", Terminal.RED))
        .appendLine(Terminal.wrap(Instant.now().toString(), Terminal.GREY))
        .appendLine(Terminal.wrap("-".repeat(55), Terminal.GREY))
        .toString())
    }

    fun drawList(pageCount: Int) {
      val paginatedOffsetStart = (page - 1) * entriesPerPage
      val paginatedOffsetEnd = ((page * entriesPerPage) - 1).coerceIn(0, entries.count() - 1)

      // Iterate over the map in order as-is and print items on the selected page
      val entriesIterator = entries.iterator()
      var i = 0
      while (i <= paginatedOffsetEnd) {
        val nextEntry = entriesIterator.next()
        if (i >= paginatedOffsetStart) {
          val eid = nextEntry.value.eid
          print(Terminal.wrap(
            str = "$eid".padStart(5, ' '),
            with = Terminal.RED.takeIf { badCryptoEid.contains(eid) } ?: Terminal.RESET))
          println(nextEntry.value.displayLine)
        }
        i++
      }

      println(Terminal.wrap("\n  (page $page of $pageCount)", Terminal.GREY))
    }

    fun drawPrompt() {
      val sb = StringBuilder()
        .append("\n")
        .appendLine(promptFeedback ?: "")
        .appendLine("Type ${Terminal.wrap("'help'", Terminal.YELLOW)} for help")
        .append("\n  ╰─> command: ")
      promptFeedback = null
      print(sb)
    }

    while (true) {
      val pageCount: Int = ceil(1.0 * entries.count() / entriesPerPage).toInt()

      Terminal.resetScreen()
      drawHeader()
      drawList(pageCount = pageCount)
      drawPrompt()

      val opts = readln().split(" ")
      if (opts.isNotEmpty()) {

        when ('p'.takeUnless{ opts[0].isNotEmpty() } ?: opts[0].lowercase()[0]) {

          // wv - get        OK
          // qh - program    OK TODO: edit help
          // p - page        OK
          // c - page count  OK
          // f - filter
          // n - new
          // d - delete
          // e - edit

          // WINDOW: slide value around, never displaying the whole thing on screen at once
          'w' -> actionViewValue(userOptions = opts, doNotWindow = false)
          // VIEW: dump entire value on-screen
          'v' -> actionViewValue(userOptions = opts, doNotWindow = true)

          // PAGE: rotate page or visit new one
          'p' -> {
            // Split behaviour here: if a new page is provided explicitly that is out of range, just coerce it.
            // If we are cycling by just pressing enter (or entering p), wrap around from pageCount to 1.
            page = if (opts.size > 1) {
              (opts[1].toIntOrNull() ?: (page + 1)).coerceIn(1, pageCount)
            } else {
              // cycle
              (page + 1).takeUnless { page == pageCount } ?: 1
            }
          }

          // COUNT: entries per page
          'c' -> {
            // will fall back silently in case of user
            entriesPerPage = (entriesPerPage.takeUnless { opts.size > 1 } ?: opts[1].toIntOrNull() ?: entriesPerPage)
              .coerceIn(1, 20)
            page = 1
            promptFeedback = Terminal.wrap("Changed to $entriesPerPage entries per page.", Terminal.GREEN)
          }

          // NEW: prompt for a new entry.
          'n' -> {
            actionPromptNewEntry()
            indexEntries()
          }

          // "HELP"
          'h' -> {
            Terminal.resetScreen()
            val helpText = StringBuilder()
              .append(Terminal.wrap("window #", Terminal.YELLOW))
              .append(Terminal.wrap(" / ", Terminal.GREY))
              .append("e.g. ")
              .appendLine(Terminal.wrap("w 2", Terminal.GREEN))
              .appendLine("╰─> Display value in entry # in a scrolling window.")
              .append("\n\nPress enter to exit this help menu.")
              .toString()
            println(helpText)
            System.console()?.readPassword() ?: readln()
            Terminal.resetScreen(lines = 99)
          }

          // QUIT to "main" "screen"
          'q' -> {
            Terminal.resetScreen()
            println("Seeya")
            return
          }

          else -> {
            promptFeedback = "Unrecognized command."
          }

        }
      }
    }
  }

}