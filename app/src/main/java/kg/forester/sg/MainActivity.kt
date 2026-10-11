package kg.forester.sg

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import com.composables.icons.lucide.*
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kg.forester.sg.data.LinkState
import kg.forester.sg.data.Repo
import kg.forester.sg.service.EcuService
import kg.forester.sg.ui.C
import kg.forester.sg.ui.DtcScreen
import kg.forester.sg.ui.FuelScreen
import kg.forester.sg.ui.HomeScreen
import kg.forester.sg.ui.LogScreen
import kg.forester.sg.ui.ProgressBar
import kg.forester.sg.ui.SettingsScreen
import kg.forester.sg.ui.T
import kg.forester.sg.ui.fmt
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

enum class Screen(val title: String, val icon: ImageVector) {
    Home("Главная", Lucide.House),
    Drive("Динамика", Lucide.Gauge),
    Fuel("Расход", Lucide.ChartColumn),
    Gearbox("АКПП", Lucide.Cog),
    Battery("АКБ", Lucide.BatteryCharging),
    Dtc("Ошибки", Lucide.TriangleAlert),
    Log("Лог", Lucide.FileText),
    Settings("Настройки", Lucide.SlidersHorizontal),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        kg.forester.sg.data.Shortcuts.load(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1
        )
        EcuService.start(this)
        setContent { App() }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // The head unit brings its bars back after dialogs/app switches; hide them again.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Let the running service pick up GPS once location is granted.
        EcuService.start(this, EcuService.ACTION_START_GPS)
    }
}

@Composable
fun App() {
    var screen by remember { mutableStateOf(Screen.Fuel) }
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0E1222), C.Bg))
        )
    ) {
        // Split screen next to the navigator: switch to the compact driver layout instead of squeezing.
        if (maxWidth < 900.dp) {
            kg.forester.sg.ui.CompactScreen()
            return@BoxWithConstraints
        }
        Row(Modifier.fillMaxSize().padding(10.dp)) {
            NavRail(screen) { screen = it }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                TopBar(screen)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.weight(1f)) {
                    when (screen) {
                        Screen.Home -> HomeScreen()
                        Screen.Drive -> kg.forester.sg.ui.DriveScreen()
                        Screen.Fuel -> FuelScreen()
                        Screen.Gearbox -> kg.forester.sg.ui.TransmissionScreen()
                        Screen.Battery -> kg.forester.sg.ui.BatteryScreen()
                        Screen.Dtc -> DtcScreen()
                        Screen.Log -> LogScreen()
                        Screen.Settings -> SettingsScreen()
                    }
                }
                Spacer(Modifier.height(10.dp))
                BottomBar()
            }
        }
    }
}

@Composable
private fun NavRail(current: Screen, onSelect: (Screen) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shortcuts by kg.forester.sg.data.Shortcuts.list.collectAsState()
    val scroll = androidx.compose.foundation.rememberScrollState()
    // The scrolling area starts below the logo; fades hint that more buttons are above/below.
    Box(Modifier.width(92.dp).fillMaxHeight().padding(top = 62.dp)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Screen.entries.forEach { s ->
                RailItem(s.icon, s.title, s == current) { onSelect(s) }
            }
            if (shortcuts.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp).height(1.dp).background(C.Border))
                shortcuts.forEach { sc ->
                    RailItem(kg.forester.sg.data.Shortcuts.icon(sc.icon), sc.label, false, accent = true) {
                        kg.forester.sg.data.Shortcuts.launch(ctx, sc)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (scroll.value > 0) {
            Box(Modifier.fillMaxWidth().height(28.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color(0xFF0D1120), Color.Transparent))))
        }
        if (scroll.value < scroll.maxValue) {
            Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF0A0C16)))))
        }
    }
}

@Composable
private fun RailItem(icon: ImageVector, title: String, selected: Boolean, accent: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) C.Purple.copy(alpha = 0.3f) else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, C.Purple, RoundedCornerShape(14.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (selected) C.Violet else if (accent) C.Blue else C.Sub, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(2.dp))
        T(title, 12.sp, if (selected) C.Text else C.Sub, maxLines = 1)
    }
}

@Composable
private fun TopBar(screen: Screen) {
    val e by Repo.live.collectAsState()
    val link by Repo.link.collectAsState()
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(5000) } }

    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        SubaruLogo(Modifier.size(width = 84.dp, height = 50.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            T("FORESTER SG5", 24.sp, weight = FontWeight.Bold)
            T("2003   EJ20 turbo", 13.sp, C.Sub)
        }
        Spacer(Modifier.weight(1f))
        Icon(Lucide.BatteryCharging, null, tint = C.Text, modifier = Modifier.size(22.dp))
        T(" ${fmt(e.batteryV?.toDouble())} V", 18.sp)
        Spacer(Modifier.width(22.dp))
        Icon(Lucide.Thermometer, null, tint = C.Text, modifier = Modifier.size(22.dp))
        T(" ${e.coolantC ?: "—"}°C", 18.sp)
        Spacer(Modifier.width(22.dp))
        val ok = link == LinkState.CONNECTED
        Icon(if (ok) Lucide.Usb else Lucide.Unplug, null, tint = if (ok) C.Green else C.Orange, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(22.dp))
        T(SimpleDateFormat("HH:mm", Locale.US).format(now), 26.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun BottomBar() {
    val e by Repo.live.collectAsState()
    val ts by Repo.trip.collectAsState()
    val gps by Repo.gps.collectAsState()
    val tank = Repo.settings.tankL
    Row(
        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(14.dp)).background(C.Card)
            .border(1.dp, C.Border, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomItem(Lucide.Fuel, "Топливо", "${fmt(ts.rangeKm, 0)} км", if (tank > 0) ((ts.fuelLeftL ?: 0.0) / tank).toFloat() else null, C.Accent, Modifier.weight(1f))
        BottomItem(Lucide.Thermometer, "Темп. ОЖ", "${e.coolantC ?: "—"}°C", ((e.coolantC ?: 0) / 110f), Brush.horizontalGradient(listOf(C.Blue, C.Violet)), Modifier.weight(1f))
        BottomItem(Lucide.BatteryCharging, "Напряжение", "${fmt(e.batteryV?.toDouble())} V", (((e.batteryV ?: 10f) - 10f) / 5f), Brush.horizontalGradient(listOf(C.Green, C.Green)), Modifier.weight(1f))
        BottomItem(Lucide.Wind, "Впуск", "${e.intakeC ?: "—"}°C", null, null, Modifier.weight(0.7f))
        val alt = gps?.altitudeM ?: e.atmKpa?.let { 44330.0 * (1 - Math.pow(it / 101.325, 0.1903)) }
        BottomItem(Lucide.Mountain, "Высота", "${fmt(alt, 0)} м", null, null, Modifier.weight(0.7f))
    }
}

@Composable
private fun BottomItem(icon: ImageVector, label: String, value: String, frac: Float?, brush: Brush?, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.Sub, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            T(label, 12.sp, C.Sub)
            T(value, 16.sp, weight = FontWeight.SemiBold)
        }
        if (frac != null && brush != null && !frac.isNaN()) {
            Spacer(Modifier.width(10.dp))
            ProgressBar(frac, brush, Modifier.width(90.dp), 6.dp)
        }
    }
}

@Composable
private fun SubaruLogo(modifier: Modifier) {
    Canvas(modifier) {
        drawOval(Color(0xFF1E3A8A), Offset.Zero, size)
        drawOval(Color(0xFFCBD5E1), Offset.Zero, size, style = Stroke(2.5f))
        val w = size.width
        val h = size.height
        fun star(cx: Float, cy: Float, r: Float) {
            val p = Path()
            for (i in 0 until 10) {
                val a = Math.toRadians(-90.0 + i * 36)
                val rr = if (i % 2 == 0) r else r * 0.42f
                val x = cx + rr * cos(a).toFloat()
                val y = cy + rr * sin(a).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            drawPath(p, Color.White)
        }
        star(w * 0.30f, h * 0.42f, h * 0.22f)
        star(w * 0.52f, h * 0.30f, h * 0.10f)
        star(w * 0.66f, h * 0.40f, h * 0.10f)
        star(w * 0.78f, h * 0.55f, h * 0.10f)
        star(w * 0.55f, h * 0.62f, h * 0.10f)
        star(w * 0.68f, h * 0.74f, h * 0.10f)
    }
}
