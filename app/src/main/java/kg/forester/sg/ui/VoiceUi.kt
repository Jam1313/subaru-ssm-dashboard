package kg.forester.sg.ui

import androidx.compose.foundation.verticalScroll

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import kg.forester.sg.service.Voice
import kotlinx.coroutines.delay

/** Short on-screen confirmation of what the voice control understood. */
@Composable
fun VoiceToast(modifier: Modifier = Modifier) {
    val t by Voice.toast.collectAsState()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(t) {
        if (t != null && SystemClock.elapsedRealtime() - t!!.first < 3000) {
            visible = true; delay(2600); visible = false
        }
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()) {
        Row(
            Modifier.clip(RoundedCornerShape(24.dp)).background(C.CardHi).border(1.dp, C.Purple, RoundedCornerShape(24.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.Mic, null, tint = C.Violet, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            T(t?.second ?: "", 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
fun VoiceSettings() {
    val ctx = LocalContext.current
    val status by Voice.status.collectAsState()
    val heard by Voice.lastHeard.collectAsState()
    var on by remember { mutableStateOf(Voice.enabled(ctx)) }
    var wake by remember { mutableStateOf(Voice.wakeWord(ctx)) }
    Row(Modifier.fillMaxSize()) {
        Panel(Modifier.width(470.dp).fillMaxHeight()) {
            PanelTitle(Lucide.Mic, "Голосовое управление")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("Голосовой ассистент", 15.sp)
                    T("Офлайн, слушает своё имя. Модель ~45 МБ скачается один раз", 11.sp, C.Sub)
                }
                Switch(on, { on = it; Voice.setEnabled(ctx, it) }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = wake, onValueChange = { wake = it }, singleLine = true,
                label = { T("Имя ассистента (на что он откликается)", 12.sp, C.Sub) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                    focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
                ),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions.Default,
            )
            Spacer(Modifier.height(8.dp))
            ActionButton("Сохранить имя", wake.isNotBlank()) {
                Voice.setWakeWord(ctx, wake)
                if (on) { Voice.stop(); Voice.start(ctx) }
            }
            Spacer(Modifier.height(12.dp))
            var gain by remember { mutableStateOf(Voice.gain(ctx)) }
            var sens by remember { mutableStateOf(Voice.sensitive(ctx)) }
            T("Чувствительность микрофона", 14.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1f to "×1", 2f to "×2", 3f to "×3", 4f to "×4").forEach { (g, l) ->
                    Chip(l, gain == g, { gain = g; Voice.setGain(ctx, g) })
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Чувствительный режим микрофона", 13.sp, modifier = Modifier.weight(1f))
                Switch(sens, { sens = it; Voice.setSensitive(ctx, it); if (on) Voice.restart(ctx) },
                    colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
            }
            Spacer(Modifier.height(10.dp))
            T("Статус: $status", 13.sp, if (status.startsWith("слушаю")) C.Green else C.Sub)
            T("Услышано: ${heard.ifBlank { "—" }}", 13.sp, C.Sub)
            Spacer(Modifier.height(12.dp))
            MediaAccessRow()
        }
        Spacer(Modifier.width(12.dp))
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            T("Команды", 16.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val w = Voice.wakeWord(ctx).replaceFirstChar { it.uppercase() }
            Column(Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                (kg.forester.sg.service.Commands.EXAMPLES.map { (cmd, what) -> "«$w, $cmd»" to what } +
                    ("«$w» … пауза … «навигатор»" to "можно и раздельно: после имени есть 6 секунд")).forEach { (cmd, what) ->
                    Column {
                        T(cmd, 14.sp)
                        T(what, 11.sp, C.Sub)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            T("«Алиса» в Яндекс Навигаторе отвечает на «Слушай, Алиса», когда навигатор на экране (в т.ч. в разделённом режиме).", 12.sp, C.Sub)
        }
    }
}

/**
 * «Перемешай», «повтор», «что играет», «нравится» in other players need their media sessions, which Android shows
 * only to an app with notification access: its state and a button to the settings, re-checked on return.
 */
@Composable
private fun MediaAccessRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var resumed by remember { mutableStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val on = remember(resumed) { kg.forester.sg.service.MediaSessions.allowed(ctx) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T("Управление плеерами: " + if (on) "есть" else "нет доступа", 13.sp, if (on) C.Green else C.Orange)
            T("Для «перемешай», «повтор», «что играет» в Яндекс Музыке, VK, Spotify нужен доступ к уведомлениям " +
                "(приложение их не читает, только видит, какой плеер играет).", 11.sp, C.Sub)
        }
        if (!on) {
            Spacer(Modifier.width(8.dp))
            Chip("Разрешить", false, {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })
        }
    }
}
