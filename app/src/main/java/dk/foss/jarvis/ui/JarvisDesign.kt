package dk.foss.jarvis.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.foss.jarvis.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

object JarvisColors {
    val Backdrop = Color(0xFF05070D)
    val WindowBg = Color(0xFF07090F)
    val Blue = Color(0xFF2E7BFF)
    val Cyan = Color(0xFF5FF4E2)
    val CyanText = Color(0xFF9FE9DF)
    val ThinkBlue = Color(0xFF7FB0FF)
    val Muted = Color(0xFF6E7E8C)
    val Muted2 = Color(0xFF6A6B78)
    val TextPrimary = Color(0xFFEAF2F4)
    val TextPrimaryAlpha = Color(0xF2FFFFFF) // white ~.94
    val TextSecondary = Color(0x8CFFFFFF) // white ~.55
    val ErrorOrange = Color(0xFFFF8A6A)
    val ErrorOrangeGlow = Color(0x26FF785A)
    val LogoChipStart = Color(0xFF0A0E1A)
    val LogoChipEnd = Color(0xFF13203A)
    val CyanBorder = Color(0x29FFFFFF) // cyan ~.16
    val CyanBorder30 = Color(0x4D5FF4E2)
    val GlassBg = Color(0x0DFFFFFF) // white ~.05
    val GlassBorder = Color(0x14FFFFFF) // white ~.08
}

val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_light, FontWeight.Light),
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

val DmSans = FontFamily(
    Font(R.font.dm_sans_regular, FontWeight.Normal),
    Font(R.font.dm_sans_medium, FontWeight.Medium),
    Font(R.font.dm_sans_semibold, FontWeight.SemiBold),
    Font(R.font.dm_sans_bold, FontWeight.Bold),
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

@Composable
fun DeepSpaceBackground(
    active: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                // Base dark gradient
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0A0E1A),
                            Color(0xFF05070D),
                        ),
                    ),
                )
                // Active overlay scrim
                if (active) {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0x2E2E7BFF),
                                Color(0xE605070D),
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.45f),
                            radius = size.width * 0.62f,
                        ),
                    )
                } else {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0x2605070D),
                                Color(0x7305070D),
                            ),
                        ),
                    )
                }
            },
    ) {
        content()
    }
}

@Composable
fun StatusTag(text: String, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "breathe")
    val breatheAlpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tagBreathe",
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .alpha(breatheAlpha)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = text.uppercase(),
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            letterSpacing = 0.18.sp,
            color = color.copy(alpha = breatheAlpha),
        )
    }
}

@Composable
fun Waveform(
    barCount: Int,
    barWidth: Dp,
    minH: Dp,
    maxH: Dp,
    modifier: Modifier = Modifier,
    color1: Color = JarvisColors.Cyan,
    color2: Color = JarvisColors.Blue,
) {
    val transition = rememberInfiniteTransition(label = "wave")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until barCount) {
            // Deterministic per-bar height (the designed "uneven bars" look)…
            val t = (abs(sin(i * 1.7) * 0.6 + sin(i * 0.7) * 0.4)).toFloat()
            val barH = minH + (maxH - minH) * t
            // …with a staggered scaleY ripple so the wave travels across the bars.
            val scale by transition.animateFloat(
                initialValue = 0.22f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 1100,
                        delayMillis = (i * 130) % 1200,
                        easing = LinearEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar$i",
            )
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(barH)
                    .graphicsLayer { scaleY = scale }
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(color1, color2),
                        ),
                    ),
            )
        }
    }
}

@Composable
fun PulseRings(
    modifier: Modifier = Modifier,
    ringColor1: Color = JarvisColors.Cyan,
    ringColor2: Color = JarvisColors.Blue,
    content: @Composable () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale1 by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(2700, easing = LinearEasing),
        ),
        label = "ring1Scale",
    )
    val alpha1 by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2700, easing = LinearEasing),
        ),
        label = "ring1Alpha",
    )
    val scale2 by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(2700, delayMillis = 1400, easing = LinearEasing),
        ),
        label = "ring2Scale",
    )
    val alpha2 by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2700, delayMillis = 1400, easing = LinearEasing),
        ),
        label = "ring2Alpha",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Ring 1
        Canvas(modifier = Modifier.size(160.dp)) {
            drawCircle(
                color = ringColor1.copy(alpha = alpha1),
                radius = size.minDimension / 2,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        // Scaled ring 1
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(scale1)
                .alpha(alpha1)
                .border(1.5.dp, ringColor1.copy(alpha = 0.5f), CircleShape),
        )
        // Ring 2 (delayed)
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(scale2)
                .alpha(alpha2)
                .border(1.dp, ringColor2.copy(alpha = 0.4f), CircleShape),
        )
        // Core glow
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            ringColor1.copy(alpha = 0.15f),
                            Color.Transparent,
                        ),
                    ),
                    CircleShape,
                ),
        )
        content()
    }
}

@Composable
fun ThinkingOrbs(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val outerRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
        ),
        label = "outerSpin",
    )
    val innerRotation by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
        ),
        label = "innerSpin",
    )
    val coreBreathe by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "coreBreathe",
    )

    Box(modifier = modifier.size(120.dp), contentAlignment = Alignment.Center) {
        // Outer ring
        Canvas(
            modifier = Modifier
                .size(100.dp)
                .graphicsLayer { rotationZ = outerRotation },
        ) {
            drawArc(
                color = JarvisColors.Cyan.copy(alpha = 0.4f),
                startAngle = 0f,
                sweepAngle = 120f,
                useCenter = false,
                style = Stroke(width = 2.dp.toPx()),
            )
            drawArc(
                color = JarvisColors.Blue.copy(alpha = 0.3f),
                startAngle = 180f,
                sweepAngle = 90f,
                useCenter = false,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        // Inner ring
        Canvas(
            modifier = Modifier
                .size(60.dp)
                .graphicsLayer { rotationZ = innerRotation },
        ) {
            drawArc(
                color = JarvisColors.Blue.copy(alpha = 0.5f),
                startAngle = 45f,
                sweepAngle = 90f,
                useCenter = false,
                style = Stroke(width = 2.dp.toPx()),
            )
            drawArc(
                color = JarvisColors.Cyan.copy(alpha = 0.35f),
                startAngle = 225f,
                sweepAngle = 70f,
                useCenter = false,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        // Core dot
        Box(
            modifier = Modifier
                .size(12.dp)
                .scale(coreBreathe)
                .background(JarvisColors.Cyan, CircleShape),
        )
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(JarvisColors.GlassBg)
            .border(1.dp, JarvisColors.CyanBorder, RoundedCornerShape(22.dp))
            .padding(20.dp),
    ) {
        content()
    }
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    accent: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(99.dp)
    val baseBg = if (accent) JarvisColors.Cyan.copy(alpha = 0.14f) else JarvisColors.GlassBg
    val baseBorder = if (accent) JarvisColors.CyanBorder30 else JarvisColors.CyanBorder
    val baseText = if (accent) JarvisColors.Cyan else JarvisColors.TextPrimary
    val bgColor = if (enabled) baseBg else baseBg.copy(alpha = 0.05f)
    val borderColor = if (enabled) baseBorder else JarvisColors.GlassBorder
    val textColor = if (enabled) baseText else baseText.copy(alpha = 0.4f)

    androidx.compose.material3.TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .clip(shape)
            .background(bgColor, shape)
            .border(1.dp, borderColor, shape),
    ) {
        Text(
            text = text,
            fontFamily = DmSans,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = textColor,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun JarvisMark(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        val heights = listOf(10.dp, 16.dp, 14.dp, 20.dp)
        val colors = listOf(JarvisColors.Cyan, JarvisColors.Blue, JarvisColors.Cyan, JarvisColors.Blue)
        heights.forEachIndexed { i, h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors[i]),
            )
        }
    }
}
