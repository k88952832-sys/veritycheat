package dev.aero.menu

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Stages the bundled injector binary and target .so in /data/local/tmp, then uses
 * the injector's `inject` and `call` subcommands to load the library into a running
 * game process and to invoke its exported LemmingRMT_* control functions. Every
 * public function here is a thin wrapper around actually running a root shell
 * command; [CallResult.log] always carries the exact commands and their exact
 * output, so nothing here should be trusted beyond what that log shows.
 */
object RootInjector {

    const val VERSION = "0.8"

    private const val STAGING_DIR = "/data/local/tmp/aero"
    private const val STAGED_INJECTOR = "$STAGING_DIR/injector"
    private const val STAGED_LIB = "$STAGING_DIR/liblemmingrmt.so"

    data class InjectResult(val success: Boolean, val log: String)
    data class CallResult(val success: Boolean, val value: Long?, val log: String)

    // Names/paths to try as the su binary, invoked through /system/bin/sh -c rather
    // than as ProcessBuilder's argv[0] directly: ProcessBuilder resolves a bare
    // command name by searching PATH itself, in user space, and throws before ever
    // calling execve if it doesn't find a real file there. Root frameworks that hook
    // execve (KernelSU included) only get a chance to act once execve is actually
    // called, so routing through a shell's own exec resolution is what lets that
    // hook fire.
    private val SU_CANDIDATES = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
    )

    private fun copyAsset(context: Context, assetName: String, outFile: File) {
        context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Runs `command` as `<suBinary> -c <command>`, itself invoked via `sh -c` so that
     * su's own name resolution happens inside the shell, not inside ProcessBuilder. */
    private fun runWith(suBinary: String, command: String): Pair<Int, String> {
        val inner = "$suBinary -c ${shellQuote(command)}"
        val process = ProcessBuilder("/system/bin/sh", "-c", inner)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        return exit to output
    }

    /** Finds a working su binary, trying [SU_CANDIDATES] in order. A candidate only
     * counts as working if `id` actually ran under it with exit code 0. */
    private fun resolveSu(log: StringBuilder): String? {
        for (candidate in SU_CANDIDATES) {
            try {
                val (exit, out) = runWith(candidate, "id")
                log.append("su candidate '$candidate' -> exit=$exit out='${out.trim()}'\n")
                if (exit == 0) return candidate
            } catch (e: Exception) {
                log.append("su candidate '$candidate' -> failed to run (${e.javaClass.simpleName}: ${e.message})\n")
            }
        }
        return null
    }

    /** Copies the injector and the library out of assets (app-writable, no root
     * needed) and stages both into /data/local/tmp as root, where the target
     * process's SELinux domain can actually open them. Returns false and appends
     * the reason to [log] on any failure. */
    private fun stageFiles(context: Context, suBinary: String, log: StringBuilder): Boolean {
        val localInjector = File(context.filesDir, "injector")
        val localLib = File(context.filesDir, "liblemmingrmt.so")
        copyAsset(context, "injector", localInjector)
        copyAsset(context, "liblemmingrmt.so", localLib)

        val stageCmd = "mkdir -p $STAGING_DIR && " +
                "cp ${localInjector.absolutePath} $STAGED_INJECTOR && " +
                "cp ${localLib.absolutePath} $STAGED_LIB && " +
                "chmod 755 $STAGING_DIR $STAGED_INJECTOR $STAGED_LIB"
        val (stageExit, stageOut) = runWith(suBinary, stageCmd)
        log.append("stage to $STAGING_DIR -> exit=$stageExit out='$stageOut'\n")
        if (stageExit != 0) return false

        // Best-effort SELinux relabel; some policies need this even after a correct
        // chmod, others ignore it. Not fatal on its own either way.
        val (chconExit, chconOut) = runWith(suBinary, "chcon u:object_r:system_file:s0 $STAGED_LIB")
        log.append("chcon (best-effort) -> exit=$chconExit out='${chconOut.trim()}'\n")
        return true
    }

    private fun findPid(suBinary: String, packageName: String, log: StringBuilder): Int? {
        val (pidExit, pidOut) = runWith(suBinary, "pidof $packageName")
        val pid = pidOut.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
        log.append("pidof $packageName -> exit=$pidExit out='${pidOut.trim()}'\n")
        return pid
    }

    /**
     * Loads liblemmingrmt.so into the running [packageName] process via a remote
     * dlopen() call. Must be run once before any [callExported]-based function will
     * find anything to call.
     */
    fun inject(context: Context, packageName: String): InjectResult {
        val log = StringBuilder()
        log.append("Aero injector v$VERSION\n")
        try {
            val suBinary = resolveSu(log) ?: run {
                log.append("No working su binary found among: ${SU_CANDIDATES.joinToString()}\n")
                return InjectResult(false, log.toString())
            }
            log.append("Using su candidate: $suBinary\n")

            if (!stageFiles(context, suBinary, log)) return InjectResult(false, log.toString())

            val (lsExit, lsOut) = runWith(suBinary, "ls -Z $STAGED_LIB")
            log.append("ls -Z -> exit=$lsExit out='${lsOut.trim()}'\n")

            val pid = findPid(suBinary, packageName, log) ?: run {
                log.append("Could not find a running process for $packageName. Start the game first.\n")
                return InjectResult(false, log.toString())
            }

            val (ctxExit, ctxOut) = runWith(suBinary, "cat /proc/$pid/attr/current")
            log.append("target SELinux context -> exit=$ctxExit out='${ctxOut.trim()}'\n")

            val (injectExit, injectOut) = runWith(suBinary, "$STAGED_INJECTOR inject $pid $STAGED_LIB")
            log.append("inject -> exit=$injectExit\n$injectOut\n")
            return InjectResult(injectExit == 0, log.toString())
        } catch (e: Exception) {
            log.append("Exception: ${e.javaClass.simpleName}: ${e.message}\n")
            return InjectResult(false, log.toString())
        }
    }

    /**
     * Calls an already-loaded exported C function named [symbol] inside the target
     * process (normally one of the LemmingRMT_* functions; see entry.cpp for the
     * exact list). [arg] is passed as the function's single integer argument if
     * given, or omitted entirely for a function that takes none; the function's
     * return value (if any) comes back as [CallResult.value].
     */
    fun callExported(context: Context, packageName: String, symbol: String, arg: Long? = null): CallResult {
        val log = StringBuilder()
        try {
            val suBinary = resolveSu(log) ?: run {
                log.append("No working su binary found among: ${SU_CANDIDATES.joinToString()}\n")
                return CallResult(false, null, log.toString())
            }
            if (!stageFiles(context, suBinary, log)) return CallResult(false, null, log.toString())

            val pid = findPid(suBinary, packageName, log) ?: run {
                log.append("Could not find a running process for $packageName. Start the game first.\n")
                return CallResult(false, null, log.toString())
            }

            val cmd = if (arg != null) "$STAGED_INJECTOR call $pid $STAGED_LIB $symbol $arg"
                      else "$STAGED_INJECTOR call $pid $STAGED_LIB $symbol"
            val (exit, out) = runWith(suBinary, cmd)
            log.append("call $symbol -> exit=$exit\n$out\n")

            val value = out.lineSequence()
                .firstOrNull { it.startsWith("result=") }
                ?.removePrefix("result=")
                ?.substringBefore(' ')
                ?.removePrefix("0x")
                ?.toLongOrNull(16)

            return CallResult(exit == 0, value, log.toString())
        } catch (e: Exception) {
            log.append("Exception: ${e.javaClass.simpleName}: ${e.message}\n")
            return CallResult(false, null, log.toString())
        }
    }

    // --- Convenience wrappers around the specific exported functions entry.cpp defines ---

    fun setEsp(context: Context, packageName: String, enabled: Boolean) =
        callExported(context, packageName, "LemmingRMT_SetEspEnabled", if (enabled) 1L else 0L)

    fun setMenuOpen(context: Context, packageName: String, open: Boolean) =
        callExported(context, packageName, "LemmingRMT_SetMenuOpen", if (open) 1L else 0L)

    fun toggleMenu(context: Context, packageName: String) =
        callExported(context, packageName, "LemmingRMT_ToggleMenu")

    /** @return true/false if the call succeeded and returned a clean 0/1, null otherwise. */
    fun isMenuOpen(context: Context, packageName: String): Boolean? {
        val r = callExported(context, packageName, "LemmingRMT_IsMenuOpen")
        return if (r.success) r.value?.let { it != 0L } else null
    }

    /** @return true/false if the call succeeded and returned a clean 0/1, null otherwise. */
    fun isOffsetProfileReady(context: Context, packageName: String): Boolean? {
        val r = callExported(context, packageName, "LemmingRMT_IsOffsetProfileReady")
        return if (r.success) r.value?.let { it != 0L } else null
    }

    fun dumpDiagnostics(context: Context, packageName: String) =
        callExported(context, packageName, "LemmingRMT_DumpDiagnostics")
}
