package kg.forester.sg.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.composables.icons.lucide.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.LinkState
import kg.forester.sg.data.Repo
import kg.forester.sg.service.EcuLink
import kg.forester.sg.ssm.EcuInit
import kg.forester.sg.ssm.SsmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** One SSM2 trouble code: a bit in the "current" (tmp) and "memorized" (mem) status bytes. */
data class DtcDef(val code: String, val desc: String, val tmpAddr: Int, val memAddr: Int, val bit: Int, val descRu: String = "")

data class DtcHit(val def: DtcDef, val current: Boolean, val memorized: Boolean)

object DtcRepo {
    private val cache = HashMap<String, List<DtcDef>>()

    fun load(ctx: Context, asset: String): List<DtcDef> = cache.getOrPut(asset) {
        val text = runCatching { ctx.assets.open(asset).bufferedReader().readText() }.getOrNull() ?: "[]"
        val arr = JSONArray(text)
        val out = ArrayList<DtcDef>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += DtcDef(
                code = o.getString("code"),
                desc = o.optString("desc"),
                tmpAddr = o.getString("tmp").removePrefix("0x").toInt(16),
                memAddr = o.getString("mem").removePrefix("0x").toInt(16),
                bit = o.getInt("bit"),
                descRu = o.optString("ru"),
            )
        }
        out
    }

    @Volatile private var hints: Map<String, String>? = null

    /**
     * What to check first for [code] (assets/dtc_hints.json): Subaru specifics from the service manuals, TSBs and
     * forums, otherwise plain OBD-II logic. 2-digit codes mean different things on the engine and the
     * transmission unit, so the key is the unit too: "ECU" for P-codes, "ECU2" / "TCU" for the 2-digit ones.
     */
    fun hint(ctx: Context, code: String, tcu: Boolean, twoDigit: Boolean): String? {
        val m = hints ?: synchronized(this) {
            hints ?: runCatching {
                val a = org.json.JSONObject(ctx.assets.open("dtc_hints.json").bufferedReader().readText()).getJSONArray("hints")
                (0 until a.length()).associate { i -> a.getJSONObject(i).let { "${it.getString("unit")}/${it.getString("code")}" to it.getString("ru") } }
            }.getOrDefault(emptyMap()).also { hints = it }
        }
        val unit = if (!twoDigit) "ECU" else if (tcu) "TCU" else "ECU2"
        return m["$unit/$code"]
    }

    /** Every OBD-II-style code (engine and transmission share it); also the descriptions for plain OBD-II. */
    fun ecuDefs(ctx: Context) = load(ctx, "ssm_dtc.json")

    /**
     * The codes [init]'s unit keeps, picked like FreeSSM (SSMFlagbyteDefinitionsInterface::setupDiagnosticCodes),
     * the same way for the engine and the transmission: flag byte 37 bit 7 = Subaru 2-digit codes at 0x8E–0x97 /
     * 0xA4–0xAD; otherwise OBD-II codes, each block only when its flag bit says the unit has it. Reading a block
     * the unit does not have returns ordinary RAM: codes "in memory" with the check engine lamp off that never clear.
     */
    fun defs(ctx: Context, init: EcuInit): List<DtcDef> =
        if (init.supports(37, 7)) load(ctx, "ssm_dtc_2digit.json")
        else ecuDefs(ctx).filter { d -> obdBlockFlags(d.tmpAddr).any { (b, bit) -> init.supports(b, bit) } }

    /** Flag bits (in [EcuInit.supports] numbering) that enable the OBD-II code block holding [tmp]. */
    private fun obdBlockFlags(tmp: Int): List<Pair<Int, Int>> = when (tmp) {
        in 0x8E..0xAD -> listOf(37 to 4, 37 to 6)
        in 0xF0..0xF3 -> listOf(36 to 0)
        in 0x123..0x12A -> listOf(47 to 7)
        in 0x150..0x154 -> listOf(47 to 6)
        in 0x160..0x164 -> listOf(47 to 5)
        in 0x174..0x17A -> listOf(47 to 4)
        in 0x1C1..0x1C6, in 0x20A..0x20D -> listOf(58 to 6)
        in 0x263..0x267 -> listOf(58 to 5)
        // blocks only RomRaider lists (0x2A6–0x2A9, 0x2B6): newest CAN ECUs, assumed to come with the 0x263 block
        else -> listOf(58 to 5)
    }

    /**
     * Reads every status byte the table refers to (in chunks) and returns the set bits. A set bit no definition
     * explains is shown as "???" with its address, like FreeSSM does: better a question than a silent miss.
     */
    suspend fun read(defs: List<DtcDef>, dest: Int): List<DtcHit>? {
        if (defs.isEmpty()) return emptyList()
        val addrs = (defs.map { it.tmpAddr } + defs.map { it.memAddr }).distinct().sorted()
        val values = HashMap<Int, Int>()
        for (chunk in addrs.chunked(30)) {
            val raw = EcuLink.use { it.read(chunk.toIntArray(), dest) } ?: return null
            chunk.forEachIndexed { i, a -> values[a] = raw[i].toInt() and 0xFF }
        }
        // 0xFF = address not implemented by this unit (returns junk), ignore it
        fun bits(a: Int) = values[a]?.takeIf { it != 0xFF } ?: 0
        val known = defs.mapNotNull { def ->
            val cur = bits(def.tmpAddr) shr def.bit and 1 == 1
            val mem = bits(def.memAddr) shr def.bit and 1 == 1
            if (cur || mem) DtcHit(def, cur, mem) else null
        }
        val defined = defs.flatMap { listOf(it.tmpAddr to it.bit, it.memAddr to it.bit) }.toSet()
        val pairs = defs.map { it.tmpAddr to it.memAddr }.distinct()
        val unknown = pairs.filter { it.first != 0xA2 }.flatMap { (tmp, mem) -> // 0xA2/0xC2: bits FreeSSM ignores
            (0..7).mapNotNull { bit ->
                val cur = bits(tmp) shr bit and 1 == 1 && (tmp to bit) !in defined
                val stored = bits(mem) shr bit and 1 == 1 && (mem to bit) !in defined
                if (!cur && !stored) null
                else DtcHit(DtcDef("???", "Unknown code: 0x%02X/0x%02X bit %d".format(tmp, mem, bit), tmp, mem, bit,
                    "Код без описания в таблице: 0x%02X/0x%02X, бит %d".format(tmp, mem, bit)), cur, stored)
            }
        }
        return known + unknown
    }

    /**
     * Standard OBD-II codes (when the car is read without SSM): stored ones count as current and memorized,
     * pending ones as current only. Descriptions come from the SSM table where the code is the same.
     */
    suspend fun readObd(ctx: Context): List<DtcHit>? {
        val (stored, pending) = withObd { it.readDtcs() } ?: return null
        val known = ecuDefs(ctx).associateBy { it.code }
        fun def(code: String) = known[code] ?: DtcDef(code, "", 0, 0, 0)
        return stored.map { DtcHit(def(it), current = true, memorized = true) } +
            (pending - stored.toSet()).map { DtcHit(def(it), current = true, memorized = false) }
    }

    suspend fun clearObd(): Boolean = withObd { it.clearDtcs() } == true

    private suspend fun <T> withObd(block: (kg.forester.sg.ssm.Obd2) -> T): T? = EcuLink.mutex.withLock {
        val o = EcuLink.obd ?: return@withLock null
        runCatching { block(o) }.getOrNull()
    }

    /**
     * SSM2 "clear memory": write 0x40 to 0x000060 (also resets learned values); the unit echoes the byte it took.
     * The codes are gone only after the ignition has been off for a few seconds (FreeSSM asks for the same).
     */
    suspend fun clear(dest: Int): Boolean = EcuLink.use { it.write(0x000060, 0x40, dest) } == 0x40
}

@Composable
fun DtcScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val link by Repo.link.collectAsState()
    val ecuInit by Repo.ecuInit.collectAsState()
    val tcuInit by Repo.tcuInit.collectAsState()
    val obdMode by Repo.obdMode.collectAsState()
    var unitTcu by remember { mutableStateOf(false) }
    if (obdMode && unitTcu) unitTcu = false
    val init = if (unitTcu) tcuInit else ecuInit
    val dest = if (unitTcu) SsmClient.TCU else SsmClient.ECU
    val defs = remember(init, unitTcu) { init?.let { DtcRepo.defs(ctx, it) } ?: emptyList() }
    var hits by remember { mutableStateOf<List<DtcHit>?>(null) }
    var status by remember { mutableStateOf("Нажмите «Прочитать ошибки»") }
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    fun readNow() {
        busy = true; status = "Чтение…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { if (obdMode) DtcRepo.readObd(ctx) else DtcRepo.read(defs, dest) }
            hits = r
            status = when {
                r == null -> "Не удалось прочитать (нет связи с ЭБУ)"
                !obdMode && init == null -> if (unitTcu) "Блок АКПП не ответил" else "Нет связи с ЭБУ"
                !obdMode && defs.isEmpty() -> "Блок не сообщает, где хранит коды ошибок"
                r.isEmpty() -> "Ошибок нет"
                else -> "Найдено: ${r.size}"
            }
            busy = false
        }
    }

    Row(Modifier.fillMaxSize()) {
        Panel(Modifier.width(320.dp).fillMaxHeight()) {
            PanelTitle(Lucide.TriangleAlert, "Коды ошибок") { InfoButton("Коды без сканера", "dtc.manual") }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Двигатель", !unitTcu, { unitTcu = false; hits = null; status = "Нажмите «Прочитать ошибки»" }, modifier = Modifier.weight(1f))
                Chip("АКПП", unitTcu, {
                    if (!obdMode) { unitTcu = true; hits = null; status = "Нажмите «Прочитать ошибки»" }
                }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            T("Связь: " + if (link == LinkState.CONNECTED) "есть" else "нет", 14.sp, if (link == LinkState.CONNECTED) C.Green else C.Orange)
            T("SSM ID: ${init?.ssmId ?: "—"}", 13.sp, C.Sub)
            T("ROM ID: ${init?.romId ?: "—"}", 13.sp, C.Sub)
            init?.let { i -> kg.forester.sg.ssm.EcuIds.car(ctx, i.romId)?.let { T(it, 13.sp, C.Sub) } }
            val vin by Repo.vin.collectAsState()
            if (!unitTcu && vin.isNotEmpty()) T("VIN: $vin", 13.sp, C.Sub)
            if (obdMode) T("Режим OBD-II: стандартные коды, АКПП недоступна", 13.sp, C.Sub)
            else if (init != null) T(
                (if (init!!.supports(37, 7)) "Коды Subaru (2-значные)" else "Коды OBD-II") + ", в таблице: ${defs.size}", 13.sp, C.Sub,
            )
            Spacer(Modifier.height(20.dp))
            ActionButton("Прочитать ошибки", !busy) { readNow() }
            Spacer(Modifier.height(10.dp))
            ActionButton("Сбросить ошибки", !busy, danger = true) { confirmClear = true }
            Spacer(Modifier.height(16.dp))
            T(status, 14.sp)
            if (unitTcu) {
                val tcu by Repo.tcu.collectAsState()
                val tcuStatus by Repo.tcuStatus.collectAsState()
                if (tcuInit == null && tcuStatus.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    T(tcuStatus, 12.sp, C.Orange)
                }
                if (tcu?.atfLamp == true || tcu?.diagLamp == true) {
                    Spacer(Modifier.height(8.dp))
                    T("Лампа AT OIL TEMP горит. Горит постоянно — перегрев ATF. Мигает после пуска (16 вспышек) — блок " +
                        "АКПП записал неисправность: прочитайте ошибки здесь.", 12.sp, C.Orange)
                }
                if (tcu?.fwdFuse == true) {
                    Spacer(Modifier.height(8.dp))
                    T("В держатель FWD вставлен предохранитель: блок АКПП отключил задний мост, на приборке горит лампа FWD. " +
                        "Это режим, а не неисправность, кода ошибки в памяти не будет.", 12.sp, C.Orange)
                }
            }
            Spacer(Modifier.weight(1f))
            T("Сброс стирает и адаптации ЭБУ (топливные коррекции, обучение детонации и холостого хода). После сброса выключите зажигание на 5–10 с.", 11.sp, C.Sub)
        }
        Spacer(Modifier.width(12.dp))
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            HeaderRow(listOf("Код", "Описание", "Текущая", "В памяти"))
            LazyColumn {
                items(hits ?: emptyList()) { h ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(h.def.code, 15.sp, C.Orange, FontWeight.Bold, Modifier.weight(0.6f))
                        Column(Modifier.weight(2.4f)) {
                            T(h.def.descRu.ifBlank { h.def.desc }, 14.sp)
                            if (h.def.descRu.isNotBlank()) T(h.def.desc, 11.sp, C.Sub)
                            val twoDigit = !h.def.code.startsWith("P") && !h.def.code.startsWith("U") && !h.def.code.startsWith("C") && !h.def.code.startsWith("B")
                            DtcRepo.hint(ctx, h.def.code, unitTcu, twoDigit)?.let { T("Что проверить: $it", 12.sp, C.Violet) }
                        }
                        T(if (h.current) "да" else "—", 14.sp, if (h.current) C.Red else C.Sub, modifier = Modifier.weight(0.5f))
                        T(if (h.memorized) "да" else "—", 14.sp, modifier = Modifier.weight(0.5f))
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Сбросить ошибки?") },
            text = {
                Text(
                    if (unitTcu) "Блок АКПП сотрёт коды и, скорее всего, сбросит адаптации переключений — первое время переключения могут быть жёстче, пока коробка обучится."
                    else "ЭБУ сотрёт коды ошибок и сбросит адаптации (топливные коррекции, обучение холостого хода). Первые километры двигатель может работать чуть иначе, пока ЭБУ заново обучится."
                )
            },
            confirmButton = {
                TextButton({
                    confirmClear = false; busy = true; status = "Сброс…"
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { if (obdMode) DtcRepo.clearObd() else DtcRepo.clear(dest) }
                        hits = null
                        status = if (ok) "Блок принял команду. Теперь выключите зажигание минимум на 5–10 секунд, включите снова, " +
                            "дождитесь связи и прочитайте ошибки: до этого коды могут ещё показываться." else "Блок не подтвердил сброс"
                        busy = false
                    }
                }) { Text("Сбросить") }
            },
            dismissButton = { TextButton({ confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
fun ActionButton(text: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(
                if (!enabled) androidx.compose.ui.graphics.SolidColor(C.Border)
                else if (danger) androidx.compose.ui.graphics.SolidColor(C.Red.copy(alpha = 0.8f)) else C.Accent
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, 15.sp, Color.White, FontWeight.SemiBold) }
}
