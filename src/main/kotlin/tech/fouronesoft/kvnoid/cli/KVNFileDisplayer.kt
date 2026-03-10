package tech.fouronesoft.kvnoid.cli

import tech.fouronesoft.kvnoid.file.KVNFileData
import tech.fouronesoft.kvnoid.util.DataSerializationUtils

// Pre-calculated static values that will be printed more often than calculated
const val PADDING_TABOVER = "   "
val DISPLAYER_INSTRUCTIONS = StringBuilder().apply {
  append("${PADDING_TABOVER}${Terminal.wrap("'q'", Terminal.RED)}: quit window\n")
  append("${PADDING_TABOVER}${Terminal.wrap("'ENTER / n #'", Terminal.GREEN)}: forward # (default 1)\n")
  append("${PADDING_TABOVER}${Terminal.wrap("'b #'", Terminal.GREEN)}: back # (default 1)\n")
  append("${PADDING_TABOVER}${Terminal.wrap("'p #'", Terminal.BLUE)}: goto #\n")
  append("${PADDING_TABOVER}${Terminal.wrap("'s'", Terminal.YELLOW)}: jump to start\n")
  append("${PADDING_TABOVER}${Terminal.wrap("'e'", Terminal.YELLOW)}: jump to end\n")
}.toString()

/**
 * Wraps logic for viewing a `KVNFileData` model.
 * Construct normally; invoke `instance.displayValueOnScreen()` to display the KVN file's decrypted data in a sliding
 * window.
 * - The user can control the position of the window over the decrypted value. This enables plausibly mild protection
 *   from screen-peekers, as only parts of the decrypted value are displayed at one time.
 * - The window size can be controlled via this fun's `windowSize` parameter. A window size of 0 will dump the decrypted
 *   value on screen without windowing it.
 */
class KVNFileDisplayer(val kvnFile: KVNFileData) {

  var displaying: Boolean = true
  var windowPosition = 0
  val decryptedVLength: Int = kvnFile.decryptedV!!.getLength()
  val strMetadataHeader: String

  init {
    // Header data is pre-calculated once, done in init block because it needs data from the kvnFile.
    val strNametag = kvnFile.metadata.nametag.toString(DataSerializationUtils.STANDARD_CHARSET)
    val strCategory = kvnFile.metadata.category.toString(DataSerializationUtils.STANDARD_CHARSET)
    val strCreated = kvnFile.metadata.dateCreated.toString()
    val strModified = kvnFile.metadata.dateModified.toString()
    strMetadataHeader = StringBuilder().append("${Terminal.wrap("KVN", Terminal.RED)} :: ")
      .appendLine(Terminal.wrap("${kvnFile.metadata.uuid}", Terminal.GREEN))
      .appendLine(":: Category: '${Terminal.wrap(strCategory, Terminal.YELLOW)}' ")
      .appendLine(":: Nametag:  '${Terminal.wrap(strNametag, Terminal.YELLOW)}' ")
      .appendLine(":: Created:  '${Terminal.wrap(strCreated, Terminal.YELLOW)}' ")
      .appendLine(":: Modified: '${Terminal.wrap(strModified, Terminal.YELLOW)}'").toString()
  }

  /**
   * Displays the pre-calculated `DISPLAYER_INSTRUCTIONS` on-screen and displays a "command" prompt to the user.
   */
  fun drawInstructionalPrompt() {
    print(
      StringBuilder("\n").appendLine(DISPLAYER_INSTRUCTIONS).append("  ╰─> command: ")
    )
  }

  /**
   * Draws a green-on-grey progress bar representing the position in the window view.
   */
  fun drawProgressBar(width: Int = 55 - (2 * PADDING_TABOVER.length)) {
    val progress = ((windowPosition + 1) * (1.0 * width / decryptedVLength)).toInt()
    print(StringBuilder().apply {
      append(PADDING_TABOVER)
      append(Terminal.GREEN.code)
      append("▓".repeat(progress))
      append(Terminal.GREY.code)
      append("░".repeat(width - progress))
      appendLine(Terminal.RESET.code)
    }.toString())
  }

  /**
   * Prompts the user for commands to control the position of the window, or just quit the view.
   *
   * All commands can be typed using the first letter of their name.
   * All this really looks at is the first character given to it
   *
   * Commands (hitting enter without typing anything defaults to "next"):
   * - **next #** -> shift window right by 1 or by (optional) # chars; stops at end
   * - **back #** -> same as next, but shifts left; stops at 0
   * - **pos #** -> set position of window explicitly to #
   * - **start** -> move window to position 0
   * - **end** -> move window to the end
   * - **quit** -> does not mutate position, tells the program to quit.
   */
  fun promptUserForPositionInputOrQuit(): Int {
    val cmds = readln().split(" ")
    val command = "next".takeIf { cmds.isEmpty() } ?: cmds[0]
    val firstChar = 'n'.takeIf { command.isEmpty() } ?: command.lowercase()[0]

    return when (firstChar) {
      // Next [amt?1]
      'n' -> (windowPosition + (1.takeIf { cmds.size == 1 } ?: (cmds[1].toIntOrNull() ?: 1))).coerceIn(
        0, decryptedVLength - 1
      )

      // Back [amt?1]
      'b' -> (windowPosition - (1.takeIf { cmds.size == 1 } ?: (cmds[1].toIntOrNull() ?: 1))).coerceIn(
        0, decryptedVLength - 1
      )

      // set Position [n?current]
      'p' -> windowPosition.takeIf { cmds.size == 1 } ?: (cmds[1].toIntOrNull() ?: windowPosition).coerceIn(
        0, decryptedVLength - 1
      )

      // Start
      's' -> 0

      // End
      'e' -> decryptedVLength - 1

      // Quit - special case
      'q' -> {
        Terminal.resetScreen()
        displaying = false
        windowPosition
      }

      else -> windowPosition
    }
  }

  /**
   * Displays the value on screen. By default, it will be windowed.
   * The user can control the position of the window via keyboard input.
   *
   * @param windowSize Int representing the size of the window. Set to `0` to dump entire value on screen.
   */
  fun displayValueOnScreen(windowSize: Int = 9.coerceIn(0, kvnFile.decryptedV!!.getLength() / 2)) {
    // A window size of 0 means we just want to dump the value in the terminal,
    // we don't need any special controls
    if (windowSize == 0) {
      Terminal.resetScreen()
      println(strMetadataHeader)

      print(Terminal.YELLOW.code)
      kvnFile.decryptedV!!.getProvider().use { provider -> provider.get().forEach { c -> print(c) } }
      print("${Terminal.RESET.code}\n\n (press Enter to close view)")

      System.console()?.readPassword() ?: readln()
      Terminal.resetScreen(99)
      return
    }

    val windowBoundaryPlusses = PADDING_TABOVER + "+".repeat(windowSize)

    while (displaying) {
      Terminal.resetScreen()
      println(strMetadataHeader)
      println(windowBoundaryPlusses)

      // Print characters out here individually
      print(Terminal.YELLOW.code)
      print(PADDING_TABOVER)
      kvnFile.decryptedV!!.getProvider().use { provider ->
        val decryptedValue: CharArray = provider.get()
        IntRange(windowPosition, windowPosition + windowSize - 1).forEach { i ->
          print(' '.takeIf { i >= decryptedVLength } ?: decryptedValue[i])
        }
      }
      println(Terminal.RESET.code)

      // Characters guide: position, length
      print(StringBuilder().apply {
        appendLine(windowBoundaryPlusses)
        append(PADDING_TABOVER)
        append(Terminal.GREY.code)
        append(
          "$windowPosition - ${
            (windowPosition + windowSize - 1).coerceAtMost(decryptedVLength - 1)
          } in $decryptedVLength total"
        )
        appendLine(Terminal.RESET.code)
      }.toString())

      // Visual progress bar
      drawProgressBar()

      // Help and prompt
      drawInstructionalPrompt()

      // "next" on Enter pressed, or take other input.
      windowPosition = promptUserForPositionInputOrQuit()
    }
  }
}