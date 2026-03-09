package tech.fouronesoft.kvnoid.cli.application

import tech.fouronesoft.kvnoid.cli.Terminal
import tech.fouronesoft.kvnoid.cli.GuaranteedDotfile
import tech.fouronesoft.kvnoid.cli.KVNVaultBrowser
import tech.fouronesoft.kvnoid.cli.Vault
import tech.fouronesoft.kvnoid.util.ObfuscatedString
import java.nio.file.Paths
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.system.exitProcess

class KVNoidCLIApplication {

  data class CLIArgument (
    val name: String,
    val help: String,
    val example: String,
    val dotfile: GuaranteedDotfile
  )

  companion object {

    private val USER_HOME = System.getProperty("user.home")
    private val PROGRAM_OPTS: Map<String, CLIArgument> = mapOf(
      "vault-location" to CLIArgument(
        name = "vault-location",
        help = "${Terminal.wrap("(abspath)", Terminal.BLUE)} " +
            "location of .kvn files; usurps dotfile setting",
        example = "/path/to/vault/location",
        dotfile = GuaranteedDotfile.readMaybeCreate(
          name = "vault-location",
          defaultValue = Path(USER_HOME, ".kvnoid", "vault").absolutePathString()),
      ),
      "allow-insecure-vault-key" to CLIArgument(
        name = "allow-insecure-vault-key",
        help = "${Terminal.wrap("(true/false)", Terminal.BLUE)} " +
            "disable 12-character minimum length check for vault key prompt?",
        example = "false",
        dotfile = GuaranteedDotfile.readMaybeCreate(
          name = "allow-insecure-vault-key",
          defaultValue = "false")
      )
    )

    /**
     * Prints usage instructions and exits code 0
     */
    fun printHelpAndQuit() {
      println(Terminal.wrap("-- kvnoid CLI --", Terminal.RED))
      println("Simple interface into my little kvnoid system\n")
      println(Terminal.wrap("jarguments", Terminal.RED))
      PROGRAM_OPTS.forEach { (name, arg) ->
        println(Terminal.wrap("--$name", Terminal.YELLOW))
        println("├─> ${arg.help}")
        println("├─> Example: ${Terminal.wrap(arg.example, Terminal.GREEN)}")
        println("╰─> Dotfile: ${Terminal.wrap(arg.dotfile.value, Terminal.GREEN)}\n")
      }
      println(Terminal.wrap("\ncommands", Terminal.RED))
      exitProcess(0)
    }

    fun parseArgs(args: Array<String>): Map<String, String> {
      val result: MutableMap<String, String> = mutableMapOf<String, String>().also {
        PROGRAM_OPTS.values.forEach { arg -> it[arg.name] = arg.dotfile.value }
      }

      if (args.isEmpty()) return result
      if ("--help" == args[0].lowercase()) printHelpAndQuit()

      fun exitWithMessage(message: String = "Exiting", status: Int = 0) {
        println(message)
        exitProcess(status)
      }

      for (i in 0..<args.size step 2) {
        // Length check
        args[i].takeIf { args[i].lowercase().length >= 2 } ?: exitWithMessage(
          message = "Argument name at position $i unrecognized; run " +
              Terminal.wrap("kvnoid --help", Terminal.YELLOW),
          status = 2)

        // Format check
        args[i].takeIf { it.startsWith("--") } ?: exitWithMessage(
          message = "Argument ${Terminal.wrap(args[i], Terminal.RED)} not in the --correct-format; run " +
              Terminal.wrap("kvnoid --help", Terminal.YELLOW),
          status = 2)

        // Argument recognized?
        val key = args[i].lowercase().substring(2)
        key.takeIf { it in PROGRAM_OPTS } ?: exitWithMessage(
          message = "Unrecognized argument ${Terminal.wrap(args[i], Terminal.RED)}; run " +
              Terminal.wrap("kvnoid --help", Terminal.YELLOW),
          status = 2)

        // Store argument's value
        result[key] = args[i + 1]
      }

      return result
    }

    /**
     * Prompt the user to enter a plaintext passphrase that will act as the "vault key",
     * which will be used for key derivation
     */
    fun promptAndCacheVaultKey(allowShortPassphrase: Boolean = false): ObfuscatedString {
      Terminal.resetScreen()

      val sb = StringBuilder()
      if (allowShortPassphrase) {
        sb.appendLine(Terminal.wrap("!".repeat(44), Terminal.RED))
        sb.appendLine(Terminal.wrap("Warning! Vault key length check is disabled.", Terminal.YELLOW))
        sb.appendLine(Terminal.wrap("Normally, vault keys are required to be a", Terminal.YELLOW))
        sb.appendLine(Terminal.wrap("minimum of 12 characters long. Be careful", Terminal.YELLOW))
        sb.appendLine(Terminal.wrap("!".repeat(44), Terminal.RED))
      }
      sb.appendLine("\nEnter a ${Terminal.wrap("vault key", Terminal.YELLOW)} to *crypt files with.")
      sb.appendLine(" Vault key management is up to you. If you used a key to encrypt an entry before, you'll need to use")
      sb.appendLine(" the same vault key again to decrypt the same entry.")
      print(sb.toString())

      while (true) {
        print("  ╰─> vault key (input hidden): ")

        // Console will be null in the IDE
        val inputChars: CharArray = System.console()?.readPassword() ?: readln().toCharArray()

        if (inputChars.isEmpty()) {
          println("\n !! -> Enter vault key and press enter\n")
          continue
        }

        if (!allowShortPassphrase && inputChars.size < 12) {
          println("\n !! -> Make sure the vault key is at least 12 characters long.\n")
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
      val allowShortPassphrase = parsedArgs["allow-insecure-vault-key"]!!.trim().toBoolean()
      KVNVaultBrowser(Vault(
        vaultPath = Paths.get(parsedArgs["vault-location"]!!),
        vaultKey = promptAndCacheVaultKey(allowShortPassphrase).also { Terminal.resetScreen() })).drawOnLoop()
    }
  }
}