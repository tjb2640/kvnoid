package tech.fouronesoft.kvnoid.cli

import tech.fouronesoft.kvnoid.cli.util.Dotfile
import tech.fouronesoft.kvnoid.file.KVNFileData
import tech.fouronesoft.kvnoid.file.KVNFileMetadata
import tech.fouronesoft.kvnoid.util.DataSerializationUtils
import tech.fouronesoft.kvnoid.util.ObfuscatedString
import java.lang.System.console
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.println
import kotlin.system.exitProcess

class KVNoidCLIApplication {

  data class CLIArgument (
    val name: String,
    val help: String,
    val example: String,
    val dotfile: Dotfile
  )

  companion object {

    val PROGRAM_OPTS: Map<String, CLIArgument> = mapOf(
      "vault-location" to CLIArgument(
        name = "vault-location",
        help = "(abspath) location of .kvn files; usurps dotfile setting",
        example = "/path/to/vault/location",
        dotfile = Dotfile.read(
          name = "vault-location",
          defaultValue = Path(System.getProperty("user.home"), ".kvnoid", "vault").absolutePathString()),
      )
    )

    fun exitWithMessage(
      message: String = "Exiting",
      status: Int = 0
    ) {
      println(message)
      exitProcess(status)
    }

    val ANSI_RESET = "\u001B[0m"
    val ANSI_RED = "\u001B[31m"
    val ANSI_YELLOW = "\u001B[33m"

    fun printHelp() {
      println("${ANSI_RED}-- kvnoid CLI --${ANSI_RESET}")
      println("Simple interface into my little kvnoid system\n")
      println("${ANSI_RED}jarguments${ANSI_RESET}")
      PROGRAM_OPTS.forEach { (name, arg) ->
        println("╭─ ${ANSI_YELLOW}--$name${ANSI_RESET} - ${arg.help}")
        println("╰─> Example: ${arg.example}")
        println("╰─> Stored:  ${arg.dotfile.value}")
      }
      println("\n${ANSI_RED}commands${ANSI_RESET}")
      println("uck")
    }

    fun parseArgs(args: Array<String>): Map<String, String> {
      val result: MutableMap<String, String> = mutableMapOf<String, String>().also {
        PROGRAM_OPTS.values.forEach { arg -> it[arg.name] = arg.dotfile.value }
      }

      if (args.isEmpty()) return result

      if ("--help" == args[0].lowercase()) {
        printHelp()
        exitProcess(0)
      }

      for (i in 0..<args.size step 2) {
        // Length check
        args[i].takeIf { args[i].lowercase().length >= 2 } ?: exitWithMessage(
          message = "Argument name at position $i unrecognized; " +
              "run ${ANSI_YELLOW}kvnoid --help${ANSI_RESET}",
          status = 2)

        // Format check
        args[i].takeIf { it.startsWith("--") } ?: exitWithMessage(
          message = "Argument ${ANSI_RED}'${args[i]}'${ANSI_RESET} not in the --correct-format; " +
              "run ${ANSI_YELLOW}kvnoid --help${ANSI_RESET}",
          status = 2)

        // Argument recognized?
        val key = args[i].lowercase().substring(2)
        key.takeIf { it in PROGRAM_OPTS } ?: exitWithMessage(
          message = "Unrecognized argument ${ANSI_RED}'${args[i]}'${ANSI_RESET}; " +
              "run ${ANSI_YELLOW}kvnoid --help${ANSI_RESET}",
          status = 2)

        // Store argument's value
        result[key] = args[i + 1]
      }

      return result
    }

    /**
     * Clear terminal and jump to top-left
     */
    fun clearTerminal() {
      print("\u001b[2J\u001b[0;0H")
    }

    /**
     * An extension function for KVNFileData to display its readable value in a window.
     */
    fun KVNFileData.displayValueInWindow(windowSize: Int = 7.coerceAtMost(this.decryptedV.size)) {
      var pos = -1
      val len = this.decryptedV.size
      val strNametag = this.metadata.nametag.toString(DataSerializationUtils.STANDARD_CHARSET)
      val strCategory = this.metadata.category.toString(DataSerializationUtils.STANDARD_CHARSET)
      while (pos < len - 1) {
        clearTerminal()
        pos++
        println("KVN :: Nametag: '$strNametag' :: Category: '$strCategory'\n-------\n")
        val posHigh = (pos + windowSize - 1).coerceAtMost(len - 1)
        val strGuide = "   [slice $pos-$posHigh :: $len total :: $windowSize window size]"
        println("+".repeat(windowSize))
        // Print out the actual value
        IntRange(pos, pos + windowSize - 1).forEach { i ->
          print(" ".takeIf { i >= len } ?: decryptedV[i].toInt().toChar()) }
        print(strGuide)
        println("\n\n-------\n(press Enter to advance window)")
        console()?.readPassword() ?: readln()
      }
      clearTerminal()
      println("Exited window for KVN data '$strNametag'")
    }

    fun KVNFileData.displayValueInTerminal() {
      clearTerminal()
      val strNametag = this.metadata.nametag.toString(DataSerializationUtils.STANDARD_CHARSET)
      val strCategory = this.metadata.category.toString(DataSerializationUtils.STANDARD_CHARSET)
      println("KVN :: Nametag: '$strNametag' :: Category: '$strCategory'\n-------\n")
      for (c in this.decryptedV) {
        print(c.toInt().toChar())
      }
      println("\n\n-------\n(press Enter to exit)")
      console()?.readPassword() ?: readln()
      clearTerminal()
      println("Exited view of KVN data '$strNametag'")
    }

    fun promptAndCacheVaultKey(): ObfuscatedString {
      println("Enter vault key to *crypt files with")
      print(" ╰─> vault key: A")
      print("\bB")

      while (true) {
        // Console will be null in the IDE
        val inputChars: CharArray = console()?.readPassword() ?: readln().toCharArray()

        if (inputChars.isEmpty()) {
          println("\n !! -> Enter vault key and press enter\n")
          continue
        }

        val inputBytes = ByteArray(inputChars.size)
        inputChars.forEachIndexed { index, ch ->
          inputBytes[index] = ch.code.toByte()
          inputChars[index] = 0.toChar()
        }

        return ObfuscatedString(
          initialValue = inputBytes,
          overwriteInitialValueSource = true
        )
      }
    }

    @JvmStatic
    fun main(args: Array<String>) {
      val parsedArgs: Map<String, String> = parseArgs(args)
      parsedArgs.forEach { (string, string1) -> println("$string = $string1") }
      val diskyData = KVNFileData(
        metadata = KVNFileMetadata(
          category = "test category".toByteArray(DataSerializationUtils.STANDARD_CHARSET),
          nametag = "test nametag".toByteArray(DataSerializationUtils.STANDARD_CHARSET),
        ),
        decryptedV = "test corpus ab".toByteArray(DataSerializationUtils.STANDARD_CHARSET)
      )
      // TODO
      diskyData.displayValueInTerminal()
      Thread.sleep(4000)
      diskyData.displayValueInWindow()
    }
  }
}