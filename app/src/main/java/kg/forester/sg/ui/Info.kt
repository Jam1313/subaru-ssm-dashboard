package kg.forester.sg.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide

/** What a card shows, how the app gets the number, and what is normal. */
data class InfoText(val what: String, val how: String, val norm: String? = null)

/** "i" next to a card title; does nothing for keys without a text in [InfoTexts]. */
@Composable
fun InfoButton(title: String, key: String = title, modifier: Modifier = Modifier) {
    val info = InfoTexts.byKey[key] ?: return
    var open by remember { mutableStateOf(false) }
    Box(
        modifier.size(28.dp).clip(CircleShape).clickable { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Lucide.Info, "Что это", tint = C.Sub.copy(alpha = 0.6f), modifier = Modifier.size(15.dp))
    }
    if (open) InfoDialog(title, info) { open = false }
}

@Composable
fun InfoDialog(title: String, info: InfoText, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = C.CardHi,
        title = { T(title, 18.sp, weight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Section("Что показывает", info.what)
                Section("Как считается", info.how)
                info.norm?.let { Section("Норма и подсказки", it) }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Понятно") } },
    )
}

@Composable
private fun Section(head: String, body: String) {
    T(head, 13.sp, C.Violet, FontWeight.SemiBold)
    Spacer(Modifier.height(3.dp))
    T(body, 14.sp)
    Spacer(Modifier.height(12.dp))
}
