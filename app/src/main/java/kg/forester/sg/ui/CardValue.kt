package kg.forester.sg.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A single number a card can show either in its normal look or as a needle gauge. */
data class CardValue(
    val icon: ImageVector,
    val title: String,
    val value: Float?,
    val min: Float,
    val max: Float,
    val unit: String,
    val format: String,
    /** Info text key for the "i" button. */
    val info: String,
    /** Start of the red zone on the gauge, if the value has a "too high". */
    val redFrom: Float? = null,
)

/** Round scale bounds and a major step giving 5–8 numbered ticks. */
private fun niceScale(min: Float, max: Float): Triple<Float, Float, Float> {
    val raw = (max - min) / 6f
    val mag = 10f.pow(floor(log10(raw)))
    val step = listOf(1f, 2f, 2.5f, 5f, 10f).map { it * mag }.first { it >= raw }
    return Triple(floor(min / step) * step, ceil(max / step) * step, step)
}

/** [m] as a needle gauge in [style], as large as the card allows, with the card title and "i" on top. */
@Composable
fun NeedleCard(m: CardValue, style: DialStyle, modifier: Modifier) {
    val (lo, hi, step) = niceScale(m.min, m.max)
    val div = if (hi >= 1000f) 1000f else 1f // rpm: numbers in thousands like the tachometer
    Panel(modifier, padding = 8.dp) {
        CardTitle(m.icon, m.title, m.info)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.padding(2.dp).aspectRatio(1f, matchHeightConstraintsFirst = maxHeight < maxWidth)) {
                Dial(m.value, hi, step, div, m.unit, if (div > 1f) "x1000" else null, m.redFrom, Modifier.fillMaxSize(), style, min = lo, format = m.format)
            }
        }
    }
}

/** [plain] unless the card was switched to a gauge design ([style] = its id). */
@Composable
fun ValueCard(m: CardValue, style: String, modifier: Modifier, plain: @Composable () -> Unit) {
    if (style.isEmpty()) plain() else NeedleCard(m, dialStyle(style), modifier)
}
