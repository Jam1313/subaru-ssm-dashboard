package kg.forester.sg.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Car
import com.composables.icons.lucide.Lucide
import kg.forester.sg.R
import kg.forester.sg.data.BrandLogos
import kg.forester.sg.data.CarKind
import kg.forester.sg.data.CarKind.LogoPick
import kg.forester.sg.data.CarKind.SplashPick
import kg.forester.sg.data.Repo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The header's logo: Subaru's, the make's by the VIN, the driver's own picture or nothing (Настройки → «Логотип в шапке»). */
@Composable
fun BrandLogo(modifier: Modifier) {
    val ctx = LocalContext.current
    val pick = rememberLogoPick()
    if (pick is LogoPick.Text && pick.download != null) {
        val slug = CarKind.make.collectAsState().value?.slug
        LaunchedEffect(slug, pick.download) { if (slug != null) BrandLogos.download(ctx, slug, pick.download) }
    }
    LogoView(pick, modifier)
}

/** «Без логотипа» in the settings: the header leaves the logo's room to the name. */
@Composable
fun logoShown(): Boolean = rememberLogoPick() != LogoPick.None

@Composable
private fun rememberLogoPick(): LogoPick {
    val ctx = LocalContext.current
    val ver by Repo.carVersion.collectAsState()
    val other by CarKind.other.collectAsState()
    val make by CarKind.make.collectAsState()
    val files by BrandLogos.version.collectAsState()
    return remember(ver, other, make, files) { BrandLogos.pick(ctx, Repo.settings.logoMode, other, make) }
}

/** A logo as [pick] says, fitted into [modifier]'s box. */
@Composable
fun LogoView(pick: LogoPick, modifier: Modifier, textColor: Color = C.Text) {
    when (pick) {
        LogoPick.None -> Box(modifier)
        LogoPick.Subaru -> Image(painterResource(R.drawable.subaru_logo), "Subaru", modifier, contentScale = ContentScale.Fit)
        LogoPick.Generic -> Box(modifier, contentAlignment = Alignment.Center) {
            Icon(Lucide.Car, null, tint = C.Sub, modifier = Modifier.fillMaxHeight(0.8f).fillMaxWidth(0.6f))
        }
        is LogoPick.Asset -> LogoPicture("asset:" + pick.path, modifier)
        is LogoPick.File -> LogoPicture(pick.path, modifier)
        is LogoPick.Text -> MakeName(pick.name, modifier, textColor)
    }
}

@Composable
private fun LogoPicture(ref: String, modifier: Modifier) {
    val ctx = LocalContext.current
    // with it: draw it light, a dark logo being invisible on the dark header (and the black start-up screen)
    val bmp by produceState<Pair<android.graphics.Bitmap, Boolean>?>(null, ref) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            BrandLogos.bitmap(ctx, ref)?.let { it to (!BrandLogos.isOwn(ref) && BrandLogos.isDark(ref, it)) }
        }
    }
    val (b, dark) = bmp ?: run { Box(modifier); return }
    Image(
        remember(b) { b.asImageBitmap() }, null, modifier, contentScale = ContentScale.Fit,
        colorFilter = if (dark) androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFFE6E8F0), androidx.compose.ui.graphics.BlendMode.SrcIn) else null,
    )
}

/** The make's name in heavy capitals, as large as the box takes. */
@Composable
private fun MakeName(name: String, modifier: Modifier, color: Color) {
    val text = name.uppercase()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val d = LocalDensity.current
        // a capital of this weight is ~0.68 of the font size wide (with the extra spacing), ~0.72 tall
        val byWidth = with(d) { (maxWidth / (text.length.coerceAtLeast(3) * 0.68f)).toSp().value }
        val byHeight = with(d) { (maxHeight * 0.62f).toSp().value }
        val sp = minOf(byWidth, byHeight).coerceIn(8f, 160f)
        Text(
            text, maxLines = 1, softWrap = false,
            style = TextStyle(fontSize = sp.sp, fontWeight = FontWeight.Black, color = color, letterSpacing = (sp * 0.04f).sp,
                textAlign = TextAlign.Center),
        )
    }
}

/**
 * The start-up screen over everything for a moment (the 3D frames load meanwhile). «Как сейчас» is the Subaru picture
 * as it always was; the others (the car's logo, an own picture, the app's name) stay a second at most, less once the
 * car's data comes. A tap closes it. The window behind is plain black, so nothing else flashes first.
 */
@Composable
fun StartSplash() {
    // once per start of the app: the activity is created again on a turn to landscape (phones), a new VIN or a
    // theme change, and a second splash then flashed after the first, possibly a different one
    if (remember { splashShown }) return
    val ctx = LocalContext.current
    // the demo drives a Subaru and the header says SUBARU: the last real car's logo (a Toyota's) would contradict it.
    // An own picture or the app's name, picked on purpose, stays
    val pick = remember {
        val s = Repo.settings
        if (s.demo && (s.splashMode == "subaru" || s.splashMode == "car")) SplashPick.Subaru else BrandLogos.splash(ctx)
    }
    DisposableEffect(Unit) { onDispose { splashShown = true } }
    // a full-screen picture stays its whole time, as the Subaru one always did
    val subaru = pick == SplashPick.Subaru || pick == SplashPick.Route
    var show by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (subaru) { delay(1800); show = false; splashShown = true; return@LaunchedEffect }
        delay(400)
        withTimeoutOrNull(600) { Repo.live.first { it.rpm != null } }
        show = false
        splashShown = true
    }
    androidx.compose.animation.AnimatedVisibility(
        show,
        enter = androidx.compose.animation.EnterTransition.None,
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(if (subaru) 600 else 300)),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .clickable(remember { MutableInteractionSource() }, null) { show = false },
            contentAlignment = Alignment.Center,
        ) {
            when (pick) {
                SplashPick.Route -> RouteSplash()
                // drawable-port has an upright version for phones, so cropping never cuts the emblem
                SplashPick.Subaru -> Image(painterResource(R.drawable.splash), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                SplashPick.Name -> AppName()
                is SplashPick.Logo -> {
                    // an own picture takes the whole screen (fitted, on black); a logo sits in the middle
                    val photo = pick.logo is LogoPick.File && Repo.settings.splashMode == "custom"
                    if (photo) LogoView(pick.logo, Modifier.fillMaxSize())
                    else LogoView(pick.logo, Modifier.fillMaxWidth(0.46f).fillMaxHeight(0.38f), Color.White)
                }
            }
        }
    }
}

/** The splash has been on in this process (see [StartSplash]). */
private var splashShown = false

/**
 * The neon road through the mountains (3:2). Its ring must stay whole on any screen: a wide unit shows it at full
 * height with the dark edges carried on by a matching background, a 16:9 one crops a little off the top and bottom,
 * a phone held upright shows it at full width.
 */
@Composable
private fun RouteSplash() {
    androidx.compose.foundation.layout.BoxWithConstraints(
        // the picture's own edges: near black on the left, a touch of blue on the right
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF000104), Color(0xFF010913)))),
        contentAlignment = Alignment.Center,
    ) {
        val ratio = maxWidth / maxHeight
        val scale = when {
            ratio >= 1.5f -> ContentScale.FillHeight
            ratio >= 1f -> ContentScale.Crop
            else -> ContentScale.FillWidth
        }
        Image(painterResource(R.drawable.splash_route), null, Modifier.fillMaxSize(), contentScale = scale)
    }
}

@Composable
private fun AppName() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            // the name has no make in it, so it fits any car
            androidx.compose.ui.res.stringResource(R.string.app_name), maxLines = 1,
            style = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 1.sp),
        )
        Spacer(Modifier.height(14.dp))
        Box(Modifier.width(120.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.Accent))
    }
}

private val logoModes = listOf("auto" to "Авто по машине", "subaru" to "Subaru", "custom" to "Своя картинка", "none" to "Без логотипа")
private val splashModes = listOf(
    "route" to "Неоновый маршрут", "subaru" to "Авто: Subaru или логотип марки", "car" to "Логотип машины",
    "custom" to "Своя картинка", "name" to "Только название",
)

/** Настройки → «Основные»: the header's logo and the start-up screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrandSettings() {
    val ctx = LocalContext.current
    val s = Repo.settings
    Repo.carVersion.collectAsState().value
    BrandLogos.version.collectAsState().value
    val pickPicture = LocalPickGaugeImage.current
    val logo = s.logoMode
    val splash = s.splashMode

    T("Логотип в шапке", 15.sp)
    T("«Авто»: Subaru или логотип марки по VIN. Нет логотипа марки — её название крупными буквами", 11.sp, C.Sub)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        logoModes.forEach { (k, l) ->
            Chip(l, logo == k, {
                // an own picture: the gallery first, the mode switches once a picture is in
                if (k == "custom" && BrandLogos.custom(ctx, "logo") == null) pickPicture("brand:logo") else s.logoMode = k
            })
        }
    }
    if (logo == "custom") {
        Spacer(Modifier.height(8.dp))
        PicturePreview(BrandLogos.custom(ctx, "logo")) { pickPicture("brand:logo") }
    }
    Spacer(Modifier.height(14.dp))
    T("Экран загрузки", 15.sp)
    T("Что показывать при запуске приложения", 11.sp, C.Sub)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        splashModes.forEach { (k, l) ->
            Chip(l, splash == k, {
                if (k == "custom" && BrandLogos.custom(ctx, "splash") == null) pickPicture("brand:splash") else s.splashMode = k
            })
        }
    }
    if (splash == "custom") {
        Spacer(Modifier.height(8.dp))
        PicturePreview(BrandLogos.custom(ctx, "splash")) { pickPicture("brand:splash") }
    }
    if (splash == "car") {
        val make = CarKind.make.collectAsState().value
        T(make?.let { "Сейчас: ${it.name}" } ?: "Марка определится по VIN, пока — название приложения или Subaru", 11.sp, C.Sub)
    }
}

@Composable
private fun PicturePreview(path: String?, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(width = 110.dp, height = 58.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)
                .border(1.dp, C.Border, RoundedCornerShape(10.dp)).padding(6.dp),
        ) { if (path != null) LogoView(LogoPick.File(path), Modifier.fillMaxSize()) }
        Spacer(Modifier.width(10.dp))
        Chip(if (path == null) "Выбрать картинку" else "Другая картинка", false, onPick)
    }
}
