package kg.forester.sg

import kg.forester.sg.ui.DriverOverlays
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
import androidx.compose.foundation.horizontalScroll
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
import kg.forester.sg.ui.LinkIcon
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
    Media("Медиа", Lucide.Music),
    Fuel("Расход", Lucide.ChartColumn),
    Gearbox("АКПП", Lucide.Cog),
    Battery("АКБ", Lucide.BatteryCharging),
    Dtc("Ошибки", Lucide.TriangleAlert),
    Sensors("Датчики", Lucide.ListChecks),
    Perf("Замеры", Lucide.Timer),
    Health("Здоровье", Lucide.HeartPulse),
    Log("Лог", Lucide.FileText),
    Apps("Приложения", Lucide.LayoutGrid),
    Settings("Настройки", Lucide.SlidersHorizontal),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        // adb: am start -n kg.forester.sg/.MainActivity --ez demo true [--ez skipSetup true]
        if (intent?.hasExtra("demo") == true) Repo.settings.demo = intent.getBooleanExtra("demo", false)
        if (intent?.getBooleanExtra("skipSetup", false) == true) Repo.settings.setupDone = true
        kg.forester.sg.data.Shortcuts.load(this)
        // once a day: is there a newer release on GitHub?
        if (Repo.settings.feature("update") && System.currentTimeMillis() - Repo.settings.lastUpdateCheckMs > 24 * 3600_000L) {
            Thread {
                val r = kg.forester.sg.data.Updater.latest() ?: return@Thread
                Repo.settings.lastUpdateCheckMs = System.currentTimeMillis()
                if (kg.forester.sg.data.Updater.isNewer(r.tag, kg.forester.sg.data.Updater.current(this))) {
                    kg.forester.sg.service.Alerts.raise("update", "Вышла версия ${r.tag.removePrefix("v")}: Настройки → Основные → «Обновление приложения»",
                        kg.forester.sg.service.Alerts.Level.INFO, 24 * 3600_000L, speak = false)
                }
            }.start()
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        // Phones get the full head-unit layout too, sideways (either way round) and scaled to fit.
        if (resources.configuration.smallestScreenWidthDp < 600) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
        EcuService.start(this)
        setContent {
            // one gallery launcher for gauge pictures at the root, so the result survives the closed picker dialog
            val pickGauge = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.GetContent()
            ) { kg.forester.sg.data.GaugeImages.onPicked(this, it) }
            androidx.compose.runtime.CompositionLocalProvider(
                kg.forester.sg.ui.LocalPickGaugeImage provides { target ->
                    kg.forester.sg.data.GaugeImages.setTarget(this, target)
                    pickGauge.launch("image/*")
                },
            ) {
                ScaledDensity {
                    Box(Modifier.fillMaxSize()) {
                        App()
                        var setup by remember { mutableStateOf(!Repo.settings.setupDone) }
                        if (setup) kg.forester.sg.ui.FirstRunSetup { setup = false }
                        Splash()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        EcuService.start(this)
    }

    // with background work off, the service (polling, logging) stops once the app leaves the screen
    override fun onStop() {
        super.onStop()
        if (!Repo.settings.backgroundWork && !isChangingConfigurations) {
            stopService(android.content.Intent(this, EcuService::class.java))
        }
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

/** Start-up picture over everything for a moment, then fades out (the 3D frames load meanwhile). */
@Composable
private fun Splash() {
    var show by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(1800); show = false }
    androidx.compose.animation.AnimatedVisibility(
        show,
        enter = androidx.compose.animation.EnterTransition.None,
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(600)),
    ) {
        // drawable-port has an upright version for phones, so cropping never cuts the emblem
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(R.drawable.splash), null,
            Modifier.fillMaxSize().background(Color.Black),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    }
}

/**
 * The layout is drawn for a 1280×720 screen at ~160 dpi. Head units that report a higher dpi get fewer dp
 * and the numbers get clipped, so by default shrink the density until a 1180×660 dp layout fits.
 * A fixed scale can be chosen in the settings.
 */
@Composable
private fun ScaledDensity(content: @Composable () -> Unit) {
    val scale by Repo.uiScale.collectAsState()
    val base = androidx.compose.ui.platform.LocalDensity.current
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val density = remember(scale, base, cfg.screenWidthDp, cfg.screenHeightDp) {
        if (scale > 0) {
            androidx.compose.ui.unit.Density(base.density * scale / 100f, base.fontScale)
        } else {
            val fit = minOf(cfg.screenWidthDp * base.density / 1180f, cfg.screenHeightDp * base.density / 660f)
            // only in landscape: an upright phone gets the compact panel instead
            val landscape = cfg.screenWidthDp > cfg.screenHeightDp
            if (landscape && fit < base.density && fit >= base.density * 0.5f) androidx.compose.ui.unit.Density(fit, 1f) else base
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density) {
        content()
    }
}

fun isPhone(ctx: android.content.Context) = ctx.resources.configuration.smallestScreenWidthDp < 600

/** Phone menu: all screens in a pop-up list (the compact panel has no side rail). */
@Composable
private fun PhoneMenu(onClose: () -> Unit, onSelect: (Screen) -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(onClick = onClose),
    ) {
        Column(
            Modifier.align(Alignment.TopStart).padding(top = 56.dp, start = 10.dp)
                .width(240.dp).clip(RoundedCornerShape(16.dp)).background(C.Card)
                .border(1.dp, C.Border, RoundedCornerShape(16.dp)).padding(vertical = 6.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        ) {
            visibleScreens().forEach { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(s) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(s.icon, null, tint = C.Sub, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    T(s.title, 16.sp)
                }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var screen by remember { mutableStateOf(if (kg.forester.sg.ui.isDefaultHome(ctx)) Screen.Home else Screen.Fuel) }
    // Back goes to the Home screen first; as the home app we never close ourselves.
    androidx.activity.compose.BackHandler(enabled = screen != Screen.Home || kg.forester.sg.ui.isDefaultHome(ctx)) {
        screen = Screen.Home
    }
    val voiceNav by kg.forester.sg.service.Voice.navigate.collectAsState()
    LaunchedEffect(voiceNav) {
        voiceNav?.second?.let {
            screen = when (it) {
                kg.forester.sg.service.VoiceTarget.HOME -> Screen.Home
                kg.forester.sg.service.VoiceTarget.DRIVE -> Screen.Drive
                kg.forester.sg.service.VoiceTarget.FUEL -> Screen.Fuel
                kg.forester.sg.service.VoiceTarget.GEARBOX -> if (Repo.settings.gearbox == "mt") screen else Screen.Gearbox
                kg.forester.sg.service.VoiceTarget.BATTERY -> Screen.Battery
                kg.forester.sg.service.VoiceTarget.DTC -> Screen.Dtc
                kg.forester.sg.service.VoiceTarget.MEDIA -> if (kg.forester.sg.service.Fyt.present(ctx)) Screen.Media else screen
            }
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0E1222), C.Bg))
        )
    ) {
        // Split screen next to the navigator: switch to the compact driver layout instead of squeezing.
        // Narrow: split screen next to the navigator on the head unit, or a phone held upright.
        if (maxWidth < 900.dp) {
            var menu by remember { mutableStateOf(false) }
            kg.forester.sg.ui.CompactScreen(onMenu = if (isPhone(ctx)) ({ menu = true }) else null)
            if (menu) PhoneMenu(onClose = { menu = false }) { s ->
                menu = false
                screen = s
                // the full screens are laid out for landscape
                if (s != Screen.Home) (ctx as? android.app.Activity)?.requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            kg.forester.sg.ui.VoiceToast(Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
            DriverOverlays()
            return@BoxWithConstraints
        }
        val navPos by Repo.navPosition.collectAsState()
        val content: @Composable (Modifier) -> Unit = { m ->
            Box(m) {
                when (screen) {
                    Screen.Home -> HomeScreen()
                    Screen.Drive -> kg.forester.sg.ui.DriveScreen()
                    Screen.Media -> kg.forester.sg.ui.MediaScreen()
                    Screen.Fuel -> FuelScreen()
                    Screen.Gearbox -> kg.forester.sg.ui.TransmissionScreen()
                    Screen.Battery -> kg.forester.sg.ui.BatteryScreen()
                    Screen.Dtc -> DtcScreen()
                    Screen.Sensors -> kg.forester.sg.ui.SensorsScreen()
                    Screen.Perf -> kg.forester.sg.ui.PerfScreen()
                    Screen.Health -> kg.forester.sg.ui.HealthScreen()
                    Screen.Log -> LogScreen()
                    Screen.Apps -> kg.forester.sg.ui.AppsScreen()
                    Screen.Settings -> SettingsScreen()
                }
            }
        }
        when (navPos) {
            "top", "bottom" -> Column(Modifier.fillMaxSize().padding(10.dp)) {
                TopBar(screen)
                Spacer(Modifier.height(8.dp))
                if (navPos == "top") { NavBar(screen) { screen = it }; Spacer(Modifier.height(8.dp)) }
                content(Modifier.weight(1f))
                Spacer(Modifier.height(8.dp))
                BottomBar()
                if (navPos == "bottom") { Spacer(Modifier.height(8.dp)); NavBar(screen) { screen = it } }
            }
            else -> Row(Modifier.fillMaxSize().padding(10.dp)) {
                if (navPos != "right") { NavRail(screen) { screen = it }; Spacer(Modifier.width(10.dp)) }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(screen)
                    Spacer(Modifier.height(10.dp))
                    content(Modifier.weight(1f))
                    Spacer(Modifier.height(10.dp))
                    BottomBar()
                }
                if (navPos == "right") { Spacer(Modifier.width(10.dp)); NavRail(screen) { screen = it } }
            }
        }
        kg.forester.sg.ui.VoiceToast(Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
        DriverOverlays()
    }
}

/** A manual gearbox has no transmission unit, so no АКПП screen. */
@Composable
private fun visibleScreens(): List<Screen> {
    Repo.carVersion.collectAsState().value
    Repo.featureVersion.collectAsState().value
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Media talks to the FYT system service: only on FYT head units (Teyes etc.), not on phones or other units
    val fyt = remember { kg.forester.sg.service.Fyt.present(ctx) }
    var list = Screen.entries.toList()
    if (Repo.settings.gearbox == "mt") list = list - Screen.Gearbox
    if (!fyt) list = list - Screen.Media
    if (isPhone(ctx)) list = list - Screen.Apps // the app launcher is for head units
    val s = Repo.settings
    if (!s.feature("sensors")) list = list - Screen.Sensors
    if (!s.feature("perf")) list = list - Screen.Perf
    if (!s.feature("health")) list = list - Screen.Health
    return list
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
            visibleScreens().forEach { s ->
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

/** Horizontal navigation for the top/bottom positions; scrolls when there are many buttons. */
@Composable
private fun NavBar(current: Screen, onSelect: (Screen) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shortcuts by kg.forester.sg.data.Shortcuts.list.collectAsState()
    val scroll = androidx.compose.foundation.rememberScrollState()
    Box(
        Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(14.dp)).background(C.Card)
            .border(1.dp, C.Border, RoundedCornerShape(14.dp))
    ) {
        Row(
            Modifier.fillMaxSize().horizontalScroll(scroll).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            visibleScreens().forEach { s ->
                Box(Modifier.width(96.dp)) { RailItem(s.icon, s.title, s == current) { onSelect(s) } }
            }
            if (shortcuts.isNotEmpty()) {
                Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(34.dp).background(C.Border))
                shortcuts.forEach { sc ->
                    Box(Modifier.width(96.dp)) {
                        RailItem(kg.forester.sg.data.Shortcuts.icon(sc.icon), sc.label, false, accent = true) {
                            kg.forester.sg.data.Shortcuts.launch(ctx, sc)
                        }
                    }
                }
            }
        }
        if (scroll.value > 0) {
            Box(Modifier.fillMaxHeight().width(28.dp).align(Alignment.CenterStart)
                .background(Brush.horizontalGradient(listOf(C.Card, Color.Transparent))))
        }
        if (scroll.value < scroll.maxValue) {
            Box(Modifier.fillMaxHeight().width(36.dp).align(Alignment.CenterEnd)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, C.Card))))
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
        SubaruLogo(Modifier.size(width = 96.dp, height = 50.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Repo.carVersion.collectAsState().value
            T(Repo.settings.carTitle, 24.sp, weight = FontWeight.Bold, maxLines = 1)
            T(Repo.settings.carSub, 13.sp, C.Sub, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        Icon(Lucide.BatteryCharging, null, tint = C.Text, modifier = Modifier.size(22.dp))
        T(" ${fmt(e.batteryV?.toDouble())} V", 18.sp)
        Spacer(Modifier.width(22.dp))
        Icon(Lucide.Thermometer, null, tint = C.Text, modifier = Modifier.size(22.dp))
        T(" ${e.coolantC ?: "—"}°C", 18.sp)
        Spacer(Modifier.width(22.dp))
        LinkIcon(24.dp)
        Spacer(Modifier.width(22.dp))
        T(SimpleDateFormat("HH:mm", Locale.US).format(now), 26.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun BottomBar() = kg.forester.sg.ui.BottomStatusBar()

@Composable
private fun SubaruLogo(modifier: Modifier) {
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(R.drawable.subaru_logo), "Subaru", modifier,
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
    )
}
