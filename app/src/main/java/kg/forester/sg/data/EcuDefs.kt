package kg.forester.sg.data

import android.content.Context
import android.net.Uri
import kg.forester.sg.ssm.RomRaiderXml
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The driver's own RomRaider logger definitions (logger.xml or a piece of one), for ECUs the bundled base doesn't know
 * or describes wrongly: old JECS units, 16-bit ECUs, a ROM without the "Direct" manifold pressure. Kept as files in
 * files/defs (they go into the backup with it), listed in the settings in the order they apply: a later file wins.
 * RomRaider lays them over its base. A parameter under RomRaider's id (E51, P90…) is picked by the engine guard by
 * itself; one under an id of its own gets its role here ([roles]).
 */
object EcuDefs {
    class Entry(val file: String, val name: String)

    /** One file as read: its parameters, or why it could not be read. [stats] is filled in by RomRaider's merge. */
    class Loaded(val entry: Entry, val defs: RomRaiderXml.Defs?, val error: String?, val stats: RomRaiderXml.Stats? = null)

    private const val DIR = "defs"
    private const val MAX_BYTES = 8 shl 20

    /** Bumped when the files change: RomRaider reads them again. */
    @Volatile var stamp = 0
        private set

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR)

    fun list(): List<Entry> = runCatching {
        val a = JSONArray(Repo.settings.ecuDefs)
        List(a.length()) { a.getJSONObject(it).let { o -> Entry(o.getString("file"), o.optString("name")) } }
    }.getOrDefault(emptyList())

    private fun save(l: List<Entry>) {
        Repo.settings.ecuDefs = JSONArray().also { a -> l.forEach { a.put(JSONObject().put("file", it.file).put("name", it.name)) } }.toString()
        stamp++
        Repo.defsVersion.value++
    }

    /** Every listed file read and parsed (a missing or broken one with its error). */
    fun read(ctx: Context): List<Loaded> = list().map { e ->
        try {
            val f = File(dir(ctx), e.file)
            if (!f.isFile) Loaded(e, null, "файл пропал") else Loaded(e, RomRaiderXml.parse(f.readBytes()), null)
        } catch (x: RomRaiderXml.BadFile) {
            Loaded(e, null, x.message)
        } catch (x: Exception) {
            Loaded(e, null, x.message ?: x.toString())
        }
    }

    /**
     * Reads [uri], checks it is a definition with SSM parameters and keeps a copy; a file of the same name replaces
     * the old one in its place. Null when it went in, otherwise what is wrong with it.
     */
    fun import(ctx: Context, uri: Uri, name: String): String? {
        val bytes = try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() <= MAX_BYTES) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toByteArray()
            } ?: return "Не удалось открыть файл"
        } catch (x: Exception) {
            return "Не удалось прочитать файл: ${x.message}"
        }
        if (bytes.size > MAX_BYTES) return "Файл больше 8 МБ: это не определение логгера"
        val defs = try {
            RomRaiderXml.parse(bytes)
        } catch (x: RomRaiderXml.BadFile) {
            return "Файл не подходит: ${x.message}"
        } catch (x: Exception) {
            return "Файл не разобрался: ${x.message}"
        }
        if (defs.params.none { it.expr != null } && defs.ext.none { it.param.expr != null })
            return "Файл не подходит: ни одной формулы, которую приложение понимает"
        val d = dir(ctx).apply { mkdirs() }
        val old = list()
        val same = old.firstOrNull { it.name == name }
        val file = same?.file ?: "def${System.currentTimeMillis()}.xml"
        File(d, file).writeBytes(bytes)
        save(if (same != null) old else old + Entry(file, name))
        return null
    }

    fun remove(ctx: Context, e: Entry) {
        File(dir(ctx), e.file).delete()
        save(list().filter { it.file != e.file })
    }

    /** Role → parameter id, for the engine guard (the driver's own addresses come before these). */
    fun roles(): Map<String, String> = runCatching {
        val o = JSONObject(Repo.settings.ecuDefRoles)
        o.keys().asSequence().associateWith { o.getString(it) }.filterValues { it.isNotEmpty() }
    }.getOrDefault(emptyMap())

    fun setRole(role: String, id: String?) {
        val o = runCatching { JSONObject(Repo.settings.ecuDefRoles) }.getOrDefault(JSONObject())
        if (id == null) o.remove(role) else o.put(role, id)
        Repo.settings.ecuDefRoles = o.toString()
        Repo.defsVersion.value++
    }
}
