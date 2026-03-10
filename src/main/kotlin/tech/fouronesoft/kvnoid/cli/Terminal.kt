package tech.fouronesoft.kvnoid.cli

/**
 * ANSI formatting values and helper functions for terminal formatting in its companion
 */
enum class Terminal(val code: String) {
  RESET("\u001B[0m"),
  RED("\u001B[31m"),
  YELLOW("\u001B[33m"),
  GREEN("\u001B[32m"),
  BLUE("\u001B[34m"),
  GREY("\u001B[90m"),
  CLEAR_TO_TOP("\u001b[2J\u001b[0;0H"),
  CLEAR_LINE("\u001b[2K");

  companion object {
    /**
     * Returns `str` prefixed using the `with` value and suffixed using `Terminal.RESET`
     */
    fun wrap(str: String, with: Terminal): String {
      return "${with.code}${str}${RESET.code}"
    }

    /**
     * Hacky way of clearing off the screen. Should be sufficient on even the crustiest terminals
     */
    fun resetScreen(lines: Int = 64) {
      print("\u001B[${lines}A")
      IntRange(0, lines.coerceAtLeast(0)).forEach { _ -> println(CLEAR_LINE.code) }
      print(CLEAR_TO_TOP.code)
    }
  }
}