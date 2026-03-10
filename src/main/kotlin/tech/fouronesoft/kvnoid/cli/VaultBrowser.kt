package tech.fouronesoft.kvnoid.cli

import tech.fouronesoft.kvnoid.file.KVNFileData
import tech.fouronesoft.kvnoid.file.KVNFileMetadata
import tech.fouronesoft.kvnoid.file.KVNFileReadWriter
import tech.fouronesoft.kvnoid.util.DataSerializationUtils
import tech.fouronesoft.kvnoid.util.ObfuscatedString
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil
import kotlin.text.appendLine

private var globalEid = 0
private val HELP_TEXT = StringBuilder().append(Terminal.wrap("window #", Terminal.YELLOW))
  .append(Terminal.wrap(" / ", Terminal.GREY)).append("e.g. ")
  .appendLine(Terminal.wrap("w 2", Terminal.GREEN))
  .appendLine("╰─> Display value in entry # in a scrolling window.")
  .append("\n\nPress enter to exit this help menu.").toString()

class KVNVaultBrowser(val vault: Vault) {

  private data class VaultEntry(
    val metadata: KVNFileMetadata, val eid: Int = (++globalEid), val displayLine: String = StringBuilder()
      // "EID" is printed in drawList() for state-based colour; the rest of this is pre-calculated here
      .append("${Terminal.wrap(" |", Terminal.GREY)} ").append(
        "${
          Terminal.wrap(
            DataSerializationUtils.byteArrayToUTF8StringLE(metadata.category), Terminal.GREEN
          )
        } "
      ).append(Terminal.wrap("@ ", Terminal.GREY)).appendLine(
        "${
          Terminal.wrap(
            DataSerializationUtils.byteArrayToUTF8StringLE(metadata.nametag), Terminal.BLUE
          )
        } "
      ).appendLine(Terminal.wrap("      |  UUID:     ${metadata.uuid}", Terminal.GREY))
      .appendLine(Terminal.wrap("      |  Created:  ${metadata.dateCreated}", Terminal.GREY))
      .append(Terminal.wrap("------|  Modified: ${metadata.dateModified}", Terminal.GREY)).toString()
  )

  var page: Int = 1
  var entriesPerPage: Int = 5
  var filterCategory: String = ""
  var filterNametag: String = ""
  var filterUUID: String = ""

  var promptFeedback: String? = "Key initialized."
  private val entries: MutableMap<UUID, VaultEntry> = mutableMapOf()
  private val eidToUUID: MutableMap<Int, UUID> = mutableMapOf()
  private val categoryToNametagIndex: MutableMap<String, MutableSet<String>> = mutableMapOf()
  private val badCryptoEid: MutableSet<Int> = mutableSetOf()

  init {
    indexEntriesHere()
  }

  /**
   * Accesses the vault when entries are mutated and reindexes everything.
   * TODO: potentially make this a more efficient process
   */
  private fun indexEntriesHere() {
    globalEid = 0
    vault.getEntries().values.sortedBy { v -> v.dateCreated }.forEach { metadata ->
      VaultEntry(metadata = metadata).also {
        entries[metadata.uuid] = it
        eidToUUID[it.eid] = metadata.uuid
        val categoryString = DataSerializationUtils.byteArrayToUTF8StringLE(metadata.category).lowercase()
        if (categoryString !in categoryToNametagIndex) {
          categoryToNametagIndex[categoryString] = mutableSetOf()
        }
        categoryToNametagIndex[categoryString]!!.add(DataSerializationUtils.byteArrayToUTF8StringLE(metadata.nametag))
      }
    }
  }

  /**
   * Action 'n' in the main window: prompt the user to create a new vault entry.
   *
   * @return Boolean true if the entry was created
   */
  private fun actionPromptNewEntry(): Boolean {

    /**
     * Prompt for a plain value that can be safely displayed as the user is typing and stored un-obfuscated in RAM.
     *
     * @param kind What we are prompting for, e.g. `"category"` or `"nametag"`
     * @param sizeLimit maximum length of the input we are taking
     * @param notIn reject inputs inside of this set
     * @return user-specified value
     */
    fun promptPlainValue(kind: String, sizeLimit: Int, notIn: Set<String>? = null): String {
      while (true) {
        print("\n  Enter a $kind (max length $sizeLimit): ")
        return readln().also {
          if (it.length > sizeLimit) {
            print("Please enter a $kind up to $sizeLimit characters in length.")
            continue
          }

          if (notIn?.contains(it) == true) {
            print("That nametag already exists under this category, please pick another one.")
            continue
          }
        }
      }
    }

    /**
     * Prompt a value which is immediately thrown into an ObfuscatedString.
     *
     * @param kind What we are prompting for, e.g. `"value"`
     * @param sizeLimit maximum length of the input we are taking
     * @return user-specified value
     */
    fun promptObfuscatedValue(kind: String, sizeLimit: Int): ObfuscatedString {
      while (true) {
        print("\n  Enter a $kind (max length $sizeLimit): ")

        System.console().readPassword().also {
          if (it.size > sizeLimit) {
            print("Please enter a $kind up to $sizeLimit characters in length.")
            it.forEachIndexed { i, _ -> it[i] = 0.toChar() }
            continue
          }
        }.also { chars ->
          return ObfuscatedString(
            initialValue = ByteArray(chars.size).also { bytes ->
              chars.forEachIndexed { i, _ ->
                bytes[i] = chars[i].code.toByte()
                chars[i] = 0.toChar()
              }
            }, overwriteInitialValueSource = true
          )
        }
      }
    }

    // Warning/instruction
    Terminal.resetScreen()
    print("You're creating a new vault entry. ")
    println("Press ${Terminal.wrap("ENTER", Terminal.YELLOW)} without typing text at any point to exit early.")
    println(Terminal.wrap("Press ENTER now to proceed", Terminal.GREY))
    System.console().readPassword()

    // Category
    Terminal.resetScreen()
    val existingCategories = categoryToNametagIndex.keys.joinToString(separator = ", ") { s ->
      "[${Terminal.wrap(s, Terminal.GREEN)}]"
    }
    println("Enter a category.")
    print("Categories already in this vault: ")
    println(existingCategories)
    val inputCategory = promptPlainValue(
      kind = "category",
      sizeLimit = KVNFileReadWriter.BYTELIMIT_CATEGORY,
    ).also { if (it.isBlank()) return false }

    // Nametag
    Terminal.resetScreen()
    val nametagsInCategory = categoryToNametagIndex[inputCategory]?.joinToString(separator = ", ") { s ->
      "[${Terminal.wrap(s, Terminal.BLUE)}]"
    } ?: "(none)"
    println("Enter a nametag unique to this category.")
    print("Nametags already in this vault: ")
    println(nametagsInCategory)
    val inputNametag = promptPlainValue(
      kind = "nametag", notIn = categoryToNametagIndex[inputCategory], sizeLimit = KVNFileReadWriter.BYTELIMIT_CATEGORY
    ).also { if (it.isBlank()) return false }

    // Encrypted value
    Terminal.resetScreen()
    println("Now enter data to encrypt. This will not be shown on screen.")
    promptObfuscatedValue(
      kind = "piece of data to encrypt", sizeLimit = KVNFileReadWriter.BYTELIMIT_V
    ).also { obfuscatedValue ->
      obfuscatedValue.getProvider().use { if (it.get().isEmpty()) return false }
      vault.createFile(
        KVNFileData(
          metadata = KVNFileMetadata(
            category = inputCategory.toByteArray(DataSerializationUtils.STANDARD_CHARSET),
            nametag = inputNametag.toByteArray(DataSerializationUtils.STANDARD_CHARSET)
          ), decryptedV = obfuscatedValue
        )
      )
      return true
    }
  }

  /**
   * Action 'v' or 'w' in the main window: display value on screen.
   */
  private fun actionViewValue(userOptions: List<String>, doNotWindow: Boolean = false) {
    vault.vaultKey.getProvider().use { vaultKeyProvider ->
      val eid = userOptions[1].toIntOrNull().also {
        if (it == null) {
          promptFeedback = Terminal.wrap("Entry ID '$it' not in number format.", Terminal.RED)
          return
        }
      }

      val uuid = eidToUUID[eid].also {
        if (it == null) {
          promptFeedback = Terminal.wrap("Entry ID '$eid' doesn't exist.", Terminal.RED)
          return
        }
      }

      val metadata = entries[uuid]?.metadata.also {
        if (it == null) {
          indexEntriesHere()
          promptFeedback = "Re-indexed entries due to a problem"
          return
        }
      }

      val kvnFileData = KVNFileData.readFromAbsolutePath(
        path = metadata!!.filePath!!, metadata = metadata, passphrase = vaultKeyProvider.get()
      ).also {
        if (it == null) {
          indexEntriesHere()
          promptFeedback = "File for entry $eid doesn't exist anymore, re-indexed entries."
          return
        }
      }

      if (kvnFileData!!.decryptedV!!.getLength() == 0) {
        badCryptoEid.add(eid!!)
        promptFeedback = Terminal.wrap("Could not decrypt entry. Wrong vault key?", Terminal.BLUE)
      } else {
        KVNFileDisplayer(kvnFileData).displayValueOnScreen(0.takeIf { doNotWindow } ?: 9)
      }
    }
  }

  /**
   * Breakout code for drawing the "KVNoid" header
   */
  private fun drawHeader() {
    print(
      StringBuilder().append(Terminal.wrap("KVNoid ", Terminal.RED))
        .appendLine(Terminal.wrap(Instant.now().toString(), Terminal.GREY))
        .appendLine(Terminal.wrap("-".repeat(55), Terminal.GREY)).toString()
    )
  }

  /**
   * Breakout code for drawing the paginated list of entries in the vault
   */
  private fun drawList(pageCount: Int) {
    val offsetStart = (page - 1) * entriesPerPage
    val offsetEnd = ((page * entriesPerPage) - 1).coerceIn(0, entries.count() - 1)

    // Iterate over the map in order as-is and print items on the selected page
    val entriesIterator = entries.iterator()
    var i = 0
    while (i <= offsetEnd) {
      val nextEntry = entriesIterator.next()
      if (i >= offsetStart) {
        val eid = nextEntry.value.eid
        print(
          Terminal.wrap(
            str = "$eid".padStart(5, ' '),
          with = Terminal.RED.takeIf { badCryptoEid.contains(eid) } ?: Terminal.RESET))
        println(nextEntry.value.displayLine)
      }
      i++
    }
    println(Terminal.wrap("\n  (page $page of $pageCount)", Terminal.GREY))
  }

  /**
   * Breakout code for drawing the command prompt tetx
   */
  private fun drawPromptAndTakeInput() {
    print(
      StringBuilder().append("\n").appendLine(promptFeedback ?: "")
        .appendLine("Type ${Terminal.wrap("'help'", Terminal.YELLOW)} for help").append("\n  ╰─> command: ")
        .also { promptFeedback = null })
  }

  fun run() {
    while (true) {
      val pageCount: Int = ceil(1.0 * entries.count() / entriesPerPage).toInt()

      Terminal.resetScreen()
      drawHeader()
      drawList(pageCount = pageCount)
      drawPromptAndTakeInput()

      val opts = readln().split(" ")
      if (opts.isNotEmpty()) {

        when ('p'.takeUnless { opts[0].isNotEmpty() } ?: opts[0].lowercase()[0]) {

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
            entriesPerPage =
              (entriesPerPage.takeUnless { opts.size > 1 } ?: opts[1].toIntOrNull() ?: entriesPerPage).coerceIn(1, 20)
            page = 1
            promptFeedback = Terminal.wrap("Changed to $entriesPerPage entries per page.", Terminal.GREEN)
          }
          // NEW: prompt for a new entry.
          'n' -> {
            actionPromptNewEntry().also { added ->
              if (added) {
                indexEntriesHere()
                page = 1
                promptFeedback = Terminal.wrap("New entry added!", Terminal.GREEN)
              } else {
                promptFeedback = "Canceled adding new value."
              }
            }
          }
          // "HELP"
          'h' -> {
            Terminal.resetScreen()
            println(HELP_TEXT)
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