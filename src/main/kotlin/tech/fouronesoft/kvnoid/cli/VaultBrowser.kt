package tech.fouronesoft.kvnoid.cli

import tech.fouronesoft.kvnoid.file.KVNFileData
import tech.fouronesoft.kvnoid.file.KVNFileMetadata
import tech.fouronesoft.kvnoid.file.KVNFileReadWriter
import tech.fouronesoft.kvnoid.util.DataSerializationUtils
import tech.fouronesoft.kvnoid.util.ObfuscatedString
import java.util.UUID
import kotlin.math.ceil
import kotlin.text.appendLine

// Value displayed when user runs "help"
private val VAULTBROWSER_HELP_TEXT = "" +
    // window
    Terminal.wrap("window <EID>", Terminal.YELLOW) + " - e.g. " + Terminal.wrap("w 2", Terminal.GREEN) +
    "\n╰─> Display value in entry <EID> in a scrolling window\n\n" +
    // view
    Terminal.wrap("view <EID>", Terminal.YELLOW) + " - e.g. " + Terminal.wrap("v 2", Terminal.GREEN) +
    "\n╰─> Display whole value in entry <EID> on screen\n\n" +
    // page
    Terminal.wrap("page <PAGENUMBER>", Terminal.YELLOW) + " - e.g. " + Terminal.wrap("p 4", Terminal.GREEN) +
    "\n╰─> Jump to page <PAGENUMBER>\n\n" +
    // count (entries per page)
    Terminal.wrap("count <PAGECOUNT>", Terminal.YELLOW) + " - e.g. " + Terminal.wrap("c 5", Terminal.GREEN) +
    "\n╰─> Change the number of entries shown per page\n\n" +
    // new
    Terminal.wrap("new", Terminal.YELLOW) + " - e.g. " + Terminal.wrap("n", Terminal.GREEN) +
    "\n╰─> Interactive prompt for entry creation\n\n" +
    // delete
    Terminal.wrap("delete <EID>", Terminal.YELLOW) + " - e.g. " + Terminal.wrap("d 38", Terminal.GREEN) +
    "\n╰─> Delete an entry by its EID\n\n" +


    Terminal.wrap("Press ENTER to exit this help menu.", Terminal.GREY)

/**
 * Provides a command-line interface that allows the user to interact with a Vault instance.
 */
class VaultBrowser(val vault: Vault) {

  private var globalEid = 0

  private data class VaultEntry(
    val metadata: KVNFileMetadata, val eid: Int, val displayLine: String = StringBuilder()
      // "EID" is printed in drawList() for state-based colour; the rest of this is pre-calculated here
      .append("${Terminal.wrap(" |", Terminal.GREY)} ").append(
        "${
          Terminal.wrap(
            String(metadata.decryptedCategory!!.getProvider().get()), Terminal.GREEN
          )
        } "
      ).append(Terminal.wrap("@ ", Terminal.GREY)).appendLine(
        "${
          Terminal.wrap(
            String(metadata.decryptedNametag!!.getProvider().get()), Terminal.BLUE
          )
        } "
      ).appendLine(Terminal.wrap("      |  UUID:     ${metadata.uuid}", Terminal.GREY))
      .appendLine(Terminal.wrap("      |  Created:  ${metadata.dateCreated}", Terminal.GREY))
      .appendLine(Terminal.wrap("      |  Modified: ${metadata.dateModified}", Terminal.GREY))
      .append(Terminal.wrap("------+------------------------------------------------", Terminal.GREY))
      .toString()
  )

  var page: Int = 1
  var entriesPerPage: Int = 5
  var filterCategory: String = ""
  var filterNametag: String = ""
  var filterUUID: String = ""

  var promptFeedback: String = "Vault initialized."
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
    entries.clear()
    eidToUUID.clear()
    categoryToNametagIndex.clear()
    badCryptoEid.clear()

    // Loop all entries in the vault and map out the info we need here
    vault.getEntries().values.sortedBy { v -> v.dateCreated }.forEach { metadata ->
      VaultEntry(metadata = metadata, eid = (++globalEid)).also { foundEntry ->
        entries[metadata.uuid] = foundEntry
        eidToUUID[foundEntry.eid] = metadata.uuid

        // Would like to associate a list of nametags to their specific categories. Pairs will be unique
        val categoryString = String(metadata.decryptedCategory!!.getProvider().get()).lowercase()
        if (categoryString !in categoryToNametagIndex) categoryToNametagIndex[categoryString] = mutableSetOf()
        categoryToNametagIndex[categoryString]!!.add(String(metadata.decryptedNametag!!.getProvider().get()))
      }
    }
  }

  /**
   * Given an EID corresponding to an entry in our entries map, return its corresponding entry's KVNFileData
   */
  private fun readFileDataFromEid(eid: Int): KVNFileData? {
    val uuid = eidToUUID[eid].also { if (it == null) return null }!!
    return vault.readEntryWithUUID(uuid).also { if (it == null) return null }!!
  }

  /**
   * Given an EID corresponding to an entry in our entries map, return its corresponding entry's metadata
   */
  private fun resolveMetadataFromEid(eid: Int): KVNFileMetadata? {
    val uuid = eidToUUID[eid].also { if (it == null) return null }!!
    return entries[uuid]!!.metadata
  }

  /**
   * Attempt to parse an EID from the user's command input (it'll be argument 1).
   */
  private fun resolveEidFromInput(input: List<String>): Int? {
    return input.getOrNull(1)?.toIntOrNull().also { if (it == null) return null }
  }

  /**
   * Set the prompt feedback to a message about bad data or wrong vault key if the message couldn't be decrypted.
   * Also adds the EID to the "badCryptoEid" set, which will cause it to turn red as visual cue when displayed.
   *
   * (A KVN file's decryptedV should never be empty, so if it turns up as such it is our sign the file is "bad.")
   */
  private fun markBadCrypto(eid: Int, fileData: KVNFileData): Boolean {
    if ((fileData.decryptedValue?.getLength() ?: 0) == 0) {
      badCryptoEid.add(eid)
      promptFeedback = Terminal.wrap("Could not decrypt entry. Wrong vault key or bad data?", Terminal.BLUE)
      return true
    }
    return false
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
    fun promptPlainValue(
        kind: String,
        sizeLimit: Int,
        notIn: Set<String>? = null,
        promptColour: Terminal = Terminal.RESET): String {

      while (true) {
        print("\n  ╰─> Enter a $kind (max length $sizeLimit): ${promptColour.code}")
        return readln().also {
          print(Terminal.RESET.code)
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
        print("\n  ╰─> Enter a $kind (max length $sizeLimit): ")

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

    val headerPage = "New entry"

    // Warning/instruction
    Terminal.resetScreen()
    drawHeaderText(page = headerPage, right = "!!")
    println(
      "You're creating a new vault entry. " +
          "Press ${Terminal.wrap("ENTER", Terminal.YELLOW)} without\n" +
          "typing text at any point to exit early.\n\n" +
          Terminal.wrap("Press ENTER now to proceed", Terminal.GREY)
    )
    System.console()?.readPassword() ?: readln()

    // Category
    Terminal.resetScreen()
    drawHeaderText(page = "$headerPage -> Category (1/3)")
    val existingCategories = categoryToNametagIndex.keys.sorted().joinToString(separator = ", ") { s ->
      "[${Terminal.wrap(s, Terminal.GREEN)}]"
    }
    println("Enter a category.\nCategories already in this vault: $existingCategories")
    val inputCategory = promptPlainValue(
      kind = "category",
      sizeLimit = KVNFileReadWriter.BYTELIMIT_CATEGORY,
      promptColour = Terminal.GREEN
    ).also { if (it.isBlank()) return false }

    // Nametag
    Terminal.resetScreen()
    drawHeaderText(page = "$headerPage -> Nametag (2/3)")
    val nametagsInCategory = categoryToNametagIndex[inputCategory]?.sorted()?.joinToString(separator = ", ") { s ->
      "[${Terminal.wrap(s, Terminal.BLUE)}]"
    } ?: "(none)"
    println(
      "Enter a nametag unique to category ${
        Terminal.wrap(
          inputCategory, Terminal.GREEN
        )
      }.\n" + "Nametags already in this category: $nametagsInCategory"
    )
    val inputNametag = promptPlainValue(
      kind = "nametag",
      notIn = categoryToNametagIndex[inputCategory],
      sizeLimit = KVNFileReadWriter.BYTELIMIT_CATEGORY,
      promptColour = Terminal.BLUE
    ).also { if (it.isBlank()) return false }

    // Encrypted value
    Terminal.resetScreen()
    drawHeaderText(page = "$headerPage -> Secret value (3/3)")
    println("Now enter data to encrypt. This will not be shown on screen.")
    promptObfuscatedValue(
      kind = "piece of data to encrypt", sizeLimit = KVNFileReadWriter.BYTELIMIT_V
    ).also { obfuscatedValue ->
      obfuscatedValue.getProvider().use { if (it.get().isEmpty()) return false }
      vault.createEntry(
        KVNFileData(
          metadata = KVNFileMetadata(
            decryptedCategory = ObfuscatedString(
              initialValue = inputCategory.toByteArray(DataSerializationUtils.STANDARD_CHARSET),
              overwriteInitialValueSource = true),
            decryptedNametag = ObfuscatedString(
              initialValue = inputNametag.toByteArray(DataSerializationUtils.STANDARD_CHARSET),
              overwriteInitialValueSource = true)
          ), decryptedValue = obfuscatedValue
        )
      )
      return true
    }
  }

  /**
   * Soft-deletes a .kvn file by moving it to .kvn.deleted
   */
  private fun actionPromptDeleteValue(userOptions: List<String>): Boolean {
    val eid: Int = resolveEidFromInput(userOptions).also {
      if (it == null) {
        promptFeedback = "Couldn't delete; entry with that ID not found."
        return false
      }
    }!!

    val metadata: KVNFileMetadata = resolveMetadataFromEid(eid).also {
      if (it == null) {
        promptFeedback = "Couldn't delete; entry with that ID not found."
        return false
      }
    }!!

    Terminal.resetScreen()
    println(Terminal.wrap("Warning!", Terminal.RED) + " you are about to delete this entry:\n\n" +
        " - Category: [${Terminal.wrap(
          str = String(metadata.decryptedCategory!!.getProvider().get()),
          with = Terminal.GREEN)}]\n" +
        " - Nametag:  [${Terminal.wrap(
          str = String(metadata.decryptedNametag!!.getProvider().get()),
          with = Terminal.BLUE)}]\n" +
        " - Created:  ${Terminal.wrap(
          str = metadata.dateCreated.toString(),
          with = Terminal.YELLOW)}\n" +
        " - Modified: ${Terminal.wrap(
          str = metadata.dateModified.toString(),
          with = Terminal.YELLOW)}\n\n" +
        "If you want to do this, type " + Terminal.wrap("delete", Terminal.YELLOW) +
        " and hit ENTER.")
    print("\n  ╰─> Type ${Terminal.wrap("delete", Terminal.YELLOW)} to confirm deletion: ${Terminal.YELLOW.code}")
    readln().also {
      if ("delete" != it) {
        promptFeedback = "Failed deletion confirmation."
        return false
      }
    }

    vault.softDeleteEntry(eidToUUID[eid]).also { result ->
      if (!result) {
        promptFeedback = "Couldn't delete; file for '$eid' is gone. Vault re-indexed."
      }
      Terminal.resetScreen()
      return result
    }
  }

  /**
   * Action 'v' or 'w' in the main window: display value on screen.
   */
  private fun actionViewValue(userOptions: List<String>, doNotWindow: Boolean = false): Boolean {
    val eid: Int = resolveEidFromInput(userOptions).also {
      if (it == null) {
        promptFeedback = "Couldn't view entry; entry with that ID not found."
        return false
      }
    }!!

    val fileData: KVNFileData = readFileDataFromEid(eid).also {
      if (it == null) {
        indexEntriesHere()
        promptFeedback = "Couldn't view entry; file for '$eid' is gone. Vault re-indexed."
        return false
      }
    }!!

    if (markBadCrypto(eid, fileData)) return false

    KVNFileDisplayer(fileData).displayValueOnScreen(0.takeIf { doNotWindow } ?: 9)
    return true
  }

  private fun drawHeaderText(page: String = "", right: String = "") {
    val left = Terminal.wrap("KVNoid", Terminal.RED) +
        " \uD83D\uDD12 Browser" +
        (" -> $page".takeUnless { page.isEmpty() } ?: "")
    val middle = " ".repeat( (9 + 55 - (left.length + right.length)).coerceAtLeast(1))
    println("${left}${middle}${right}\n")
  }

  /**
   * Breakout code for drawing the paginated list of entries in the vault
   */
  private fun drawList(pageCount: Int) {
    val offsetStart = (page - 1) * entriesPerPage
    val offsetEnd = ((page * entriesPerPage) - 1).coerceIn(0, (entries.count() - 1).coerceAtLeast(0))

    // Page number and header at top
    drawHeaderText(right = "(page $page of $pageCount)")
    println("  EID " + Terminal.wrap("|", Terminal.GREY) + " Info" + "\n" +
        Terminal.wrap("------+------------------------------------------------", Terminal.GREY))

    // Iterate over the map in order as-is and print items on the selected page
    val entriesIterator = entries.iterator()
    var i = 0
    while (i <= offsetEnd && entriesIterator.hasNext()) {
      val thisEntry = entriesIterator.next()
      if (i >= offsetStart) {
        // We manually include EID here because it might be red, and we can't precalculate that like the rest of
        // the entry's displayLine.
        val eid = thisEntry.value.eid
        println(
          Terminal.wrap(
          str = "$eid".padStart(5, ' '),
          with = Terminal.RED.takeIf { badCryptoEid.contains(eid) } ?: Terminal.RESET) + thisEntry.value.displayLine)
      }
      i++
    }
  }

  /**
   * Breakout code for drawing the command prompt text
   */
  private fun drawPromptAndTakeInput() {
    print(
      StringBuilder()
        .append("\n")
        .appendLine(promptFeedback)
        .appendLine("Type ${Terminal.wrap("'help'", Terminal.YELLOW)} for help or enter a command")
        .append("\n  ╰─> command: ${Terminal.GREEN.code}")
        .also { promptFeedback = "" })
  }

  fun run() {
    while (true) {
      val pageCount: Int = ceil(1.0 * entries.count() / entriesPerPage).toInt()

      // Draw browser screen
      Terminal.resetScreen()
      drawList(pageCount = pageCount)
      drawPromptAndTakeInput()

      // Take input
      val opts = readln().split(" ")
      if (opts.isNotEmpty()) {

        when ('p'.takeUnless { opts[0].isNotEmpty() } ?: opts[0].lowercase()[0]) {

          // WINDOW: slide value around, never displaying the whole thing on screen at once
          'w' -> if (actionViewValue(userOptions = opts, doNotWindow = false)) promptFeedback = "Exited window."

          // VIEW: dump entire value on-screen
          'v' -> if (actionViewValue(userOptions = opts, doNotWindow = true)) promptFeedback = "Exited view."

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
            promptFeedback = "Changed to $entriesPerPage entries per page."
          }

          // NEW: prompt for a new entry.
          'n' -> {
            if (actionPromptNewEntry()) {
              indexEntriesHere()
              promptFeedback = Terminal.wrap("New entry added!", Terminal.GREEN)
            } else {
              promptFeedback = "Canceled new entry.".takeIf { promptFeedback.isBlank() } ?: promptFeedback
            }
          }

          // DELETE: prompt for deleting an entry.
          'd' -> {
            if (actionPromptDeleteValue(userOptions = opts)) {
              indexEntriesHere()
              page = 1
              promptFeedback = Terminal.wrap("Deleted entry.", Terminal.GREEN)
            } else {
              promptFeedback = "Canceled deleting entry.".takeIf { promptFeedback.isBlank() } ?: promptFeedback
            }
          }

          // "HELP"
          'h' -> {
            Terminal.resetScreen()
            drawHeaderText(page = "Help")
            println(VAULTBROWSER_HELP_TEXT)
            System.console()?.readPassword() ?: readln()
            Terminal.resetScreen(lines = 99)
          }

          // QUIT to "main" "screen"
          'q' -> {
            Terminal.resetScreen()
            println("Seeya")
            return
          }

          else -> promptFeedback = "Unrecognized command."
        }
      }
    }
  }

}