package kg.forester.sg.ssm

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Raw exchange log for testers (Настройки → «Журнал обмена»): every frame to and from the adapter,
 * written to logs/ssm_trace.txt so a non-working car can be debugged from a file.
 */
object SsmTrace {
    @Volatile var enabled = false
    private var file: File? = null
    private var w: FileWriter? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        if (file != null) return
        val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
        file = File(dir, "ssm_trace.txt")
    }

    val path: String? get() = file?.absolutePath

    @Synchronized
    fun log(msg: String) {
        if (!enabled) return
        val f = file ?: return
        runCatching {
            if (w == null || f.length() > 4_000_000) {
                w?.close()
                w = FileWriter(f, f.length() <= 4_000_000)
            }
            w!!.write("${fmt.format(Date())} $msg\n")
            w!!.flush()
        }
    }

    fun log(dir: String, b: ByteArray, len: Int = b.size) {
        if (enabled) log("$dir ${b.copyOf(len).toHex(" ")}")
    }

    @Synchronized
    fun clear() {
        runCatching { w?.close() }
        w = null
        file?.delete()
    }
}

fun ByteArray.toHex(sep: String): String = joinToString(sep) { "%02X".format(it.toInt() and 0xFF) }
