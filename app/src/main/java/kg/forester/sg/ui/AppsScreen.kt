package kg.forester.sg.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.composables.icons.lucide.*
import kg.forester.sg.data.Shortcuts

/** App drawer, so the dashboard can be used as the home screen. */
@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    val apps = remember { Shortcuts.installedApps(ctx) }
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { T("Поиск приложения", 14.sp, C.Sub) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                    focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
                ),
                modifier = Modifier.width(420.dp),
            )
            Spacer(Modifier.weight(1f))
            Chip("Штатный лаунчер Teyes", false, { openStockLauncher(ctx) }, Lucide.LayoutGrid)
        }
        Spacer(Modifier.height(12.dp))
        val list = apps.filter { query.isBlank() || it.label.contains(query, true) }
        LazyVerticalGrid(GridCells.Adaptive(118.dp), Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.pkg }) { a ->
                Column(
                    Modifier.clip(RoundedCornerShape(14.dp)).clickable {
                        ctx.packageManager.getLaunchIntentForPackage(a.pkg)?.let {
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); runCatching { ctx.startActivity(it) }
                        }
                    }.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val bmp = remember(a.pkg) { runCatching { a.icon?.toBitmap(128, 128)?.asImageBitmap() }.getOrNull() }
                    if (bmp != null) Image(bmp, a.label, Modifier.size(64.dp)) else Spacer(Modifier.size(64.dp))
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.Text(
                        a.label, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                        style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = C.Text),
                    )
                }
            }
        }
    }
}

/** Opens the Teyes launcher even when our app is the default home screen. */
fun openStockLauncher(ctx: Context) {
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        .setClassName("com.android.launcher8", "com.android.launcher8.Launcher")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(i) }
}

/** True when this app is the default home screen. */
fun isDefaultHome(ctx: Context): Boolean {
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return ctx.packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName == ctx.packageName
}

/**
 * Makes this app (or the Teyes launcher) the default home screen. Needs root; a flag file lets the boot script
 * re-apply it in case the firmware resets the preferred home after a reboot.
 */
fun setDefaultHome(ctx: Context, ours: Boolean) {
    val comp = if (ours) "${ctx.packageName}/.MainActivity" else "com.android.launcher8/.Launcher"
    val flag = if (ours) "touch /data/adb/forester_home" else "rm -f /data/adb/forester_home"
    val boot = "/data/adb/service.d/forester_home.sh"
    val script = """
        $flag
        cat > $boot <<'EOS'
#!/system/bin/sh
until [ "${'$'}(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 3
[ -f /data/adb/forester_home ] && pm path ${ctx.packageName} >/dev/null && cmd package set-home-activity ${ctx.packageName}/.MainActivity
EOS
        chmod 755 $boot
        cmd package set-home-activity $comp
    """.trimIndent()
    val ok = runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", script)).waitFor() == 0 }.getOrDefault(false)
    if (!ok) runCatching {
        ctx.startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
