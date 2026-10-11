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

    fun ecuDefs(ctx: Context) = load(ctx, "ssm_dtc.json")

    /** TCU init byte 37 bit 7 selects Subaru 2-digit codes vs OBD-II codes. */
    fun tcuDefs(ctx: Context, init: EcuInit?): List<DtcDef> {
        val twoDigit = init?.supports(37, 7) ?: false
        return load(ctx, if (twoDigit) "tcu_dtc_2digit.json" else "tcu_dtc_obd.json")
    }

    /** Reads every status byte the table refers to (in chunks) and returns the set bits. */
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
        return defs.mapNotNull { def ->
            val cur = bits(def.tmpAddr) shr def.bit and 1 == 1
            val mem = bits(def.memAddr) shr def.bit and 1 == 1
            if (cur || mem) DtcHit(def, cur, mem) else null
        }
    }

    /** SSM2 "clear memory": write 0x40 to 0x000060 (also resets learned values). */
    suspend fun clear(dest: Int): Boolean = EcuLink.use { it.write(0x000060, 0x40, dest) } != null
}

@Composable
fun DtcScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val link by Repo.link.collectAsState()
    val ecuInit by Repo.ecuInit.collectAsState()
    val tcuInit by Repo.tcuInit.collectAsState()
    var unitTcu by remember { mutableStateOf(false) }
    val init = if (unitTcu) tcuInit else ecuInit
    val dest = if (unitTcu) SsmClient.TCU else SsmClient.ECU
    val defs = if (unitTcu) DtcRepo.tcuDefs(ctx, tcuInit) else DtcRepo.ecuDefs(ctx)
    var hits by remember { mutableStateOf<List<DtcHit>?>(null) }
    var status by remember { mutableStateOf("Нажмите «Прочитать ошибки»") }
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    fun readNow() {
        busy = true; status = "Чтение…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { DtcRepo.read(defs, dest) }
            hits = r
            status = when {
                r == null -> "Не удалось прочитать (нет связи с ЭБУ)"
                defs.isEmpty() -> "Таблица кодов ошибок не загружена"
                r.isEmpty() -> "Ошибок нет"
                else -> "Найдено: ${r.size}"
            }
            busy = false
        }
    }

    Row(Modifier.fillMaxSize()) {
        Panel(Modifier.width(320.dp).fillMaxHeight()) {
            PanelTitle(Lucide.TriangleAlert, "Коды ошибок")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Двигатель", !unitTcu, { unitTcu = false; hits = null; status = "Нажмите «Прочитать ошибки»" }, modifier = Modifier.weight(1f))
                Chip("АКПП", unitTcu, { unitTcu = true; hits = null; status = "Нажмите «Прочитать ошибки»" }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            T("Связь: " + if (link == LinkState.CONNECTED) "есть" else "нет", 14.sp, if (link == LinkState.CONNECTED) C.Green else C.Orange)
            T("SSM ID: ${init?.ssmId ?: "—"}", 13.sp, C.Sub)
            T("ROM ID: ${init?.romId ?: "—"}", 13.sp, C.Sub)
            T("Кодов в таблице: ${defs.size}", 13.sp, C.Sub)
            Spacer(Modifier.height(20.dp))
            ActionButton("Прочитать ошибки", !busy) { readNow() }
            Spacer(Modifier.height(10.dp))
            ActionButton("Сбросить ошибки", !busy, danger = true) { confirmClear = true }
            Spacer(Modifier.height(16.dp))
            T(status, 14.sp)
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
                        val ok = withContext(Dispatchers.IO) { DtcRepo.clear(dest) }
                        status = if (ok) "Команда сброса принята. Выключите зажигание на 5–10 секунд и включите снова, затем прочитайте ошибки." else "Сброс не удался"
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
