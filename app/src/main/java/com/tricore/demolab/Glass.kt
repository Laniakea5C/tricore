package com.tricore.demolab

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.roundToInt

// --------------------------------------------------------------------------- state

class BackdropState {
    var image by mutableStateOf<ImageBitmap?>(null)
    var scale by mutableStateOf(0.125f)
    var version by mutableStateOf(0)
}

val LocalBackdrop = staticCompositionLocalOf<BackdropState?> { null }

/** Renders the app's ambient gradient once into a small bitmap — no screen capture. */
fun buildBackdrop(width: Int, height: Int): ImageBitmap {
    val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(0xFF06070B.toInt())
    fun glow(cx: Float, cy: Float, radius: Float, color: Int) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.shader = android.graphics.RadialGradient(
            cx, cy, radius,
            intArrayOf(color, color and 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, paint)
    }
    glow(width * 0.18f, height * 0.10f, width * 0.75f, 0x4D7C6CF0)
    glow(width * 0.92f, height * 0.32f, width * 0.75f, 0x33F5A34A)
    glow(width * 0.25f, height * 0.80f, width * 0.85f, 0x3835D0B0)
    return bmp.asImageBitmap()
}

@Composable
fun rememberTimeSeconds(): () -> Float {
    val state = remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (now - last >= 33_000_000L) {
                    last = now
                    state.value = (now - start) / 1_000_000_000f
                }
            }
        }
    }
    return { state.value }
}

// --------------------------------------------------------------------------- shader

/**
 * Rim-only refraction: distortion is zero in the centre and grows towards the
 * edge, so the blurred base stays legible while the border visibly bends light.
 */
private const val RIM_AGSL = """
uniform shader uContent;
uniform float2 uSize;
uniform float2 uOffset;
uniform float uScale;
uniform float uRadius;
uniform float uTime;
uniform float uRefract;
uniform float uBand;
uniform float3 uTint;

float sdRound(float2 p, float2 hs, float r) {
    float2 q = abs(p) - hs + float2(r, r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 p) {
    float2 hs = uSize * 0.5;
    float d = sdRound(p - hs, hs, uRadius);
    float band = smoothstep(-uBand, 0.0, d);
    float2 dir = normalize(p - hs + float2(0.0001, 0.0001));

    float2 bend = dir * (band * band) * uRefract;
    float wob = sin(uTime * 0.85 + p.y * 0.045) + cos(uTime * 0.63 + p.x * 0.052);
    float2 sp = p + bend + dir * wob * band * 0.9;
    float2 tex = (uOffset + sp) * uScale;

    half4 base = uContent.eval(tex);
    float chroma = smoothstep(0.55, 1.0, band) * 1.6;
    half r = uContent.eval(tex + dir * chroma).r;
    half b = uContent.eval(tex - dir * chroma).b;
    half3 col = half3(r, base.g, b);

    col = mix(col, half3(uTint), 0.10);
    col += band * 0.16;

    float alpha = band * 0.68;
    return half4(col * alpha, alpha);
}
"""

private var rimShader: RuntimeShader? = null
private var rimPaint: Paint? = null
private var strokePaint: Paint? = null

private fun ensureRim(): Boolean {
    if (Build.VERSION.SDK_INT < 33) return false
    if (rimPaint != null) return true
    return runCatching {
        rimShader = RuntimeShader(RIM_AGSL)
        rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = rimShader }
        strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        true
    }.getOrDefault(false)
}

private fun DrawScope.drawRim(
    image: ImageBitmap,
    backdropScale: Float,
    origin: Offset,
    sizePx: Size,
    radiusPx: Float,
    time: Float,
    tint: Color,
    refract: Float,
    bandPx: Float,
): Boolean {
    if (!ensureRim()) return false
    val bitmap = image.softwareBitmap() ?: return false
    val rim = rimPaint ?: return false
    val stroke = strokePaint ?: return false

    val sample = Matrix().apply {
        setScale(backdropScale, backdropScale)
        postTranslate(origin.x * backdropScale, origin.y * backdropScale)
    }
    val bitmapShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setLocalMatrix(sample)
    }

    val w = sizePx.width
    val h = sizePx.height
    val r = radiusPx.coerceAtMost(minOf(w, h) / 2f)

    val outer = Path().apply {
        addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(r, r)))
    }
    val inset = max(bandPx, 1f)
    val inner = Path().apply {
        addRoundRect(
            RoundRect(
                Rect(inset, inset, w - inset, h - inset),
                CornerRadius(max(r - inset, 0f), max(r - inset, 0f))
            )
        )
    }
    val ring = Path().apply { op(outer, inner, PathOperation.Difference) }

    val shader = rimShader ?: return false
    shader.setInputShader("uContent", bitmapShader)
    shader.setFloatUniform("uSize", w, h)
    shader.setFloatUniform("uOffset", origin.x, origin.y)
    shader.setFloatUniform("uScale", backdropScale)
    shader.setFloatUniform("uRadius", r)
    shader.setFloatUniform("uTime", time)
    shader.setFloatUniform("uRefract", refract)
    shader.setFloatUniform("uBand", bandPx)
    shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue)

    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        clipPath(ring) {
            native.drawRoundRect(0f, 0f, w, h, r, r, rim)
        }
        stroke.strokeWidth = 1f
        stroke.color = 0x30AFF6FF
        stroke.shader = null
        native.drawRoundRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, r, r, stroke)
    }
    return true
}

private fun ImageBitmap.softwareBitmap(): android.graphics.Bitmap? = runCatching {
    val bmp = asAndroidBitmap()
    if (bmp.config == android.graphics.Bitmap.Config.HARDWARE) {
        bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
    } else bmp
}.getOrNull()

// --------------------------------------------------------------------------- glass

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    corner: Dp = 26.dp,
    tint: Color = Color(0xFF0C1622),
    refract: Float = 12f,
    blur: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalBackdrop.current
    val density = LocalDensity.current
    val shape = RoundedCornerShape(corner)
    val blurPx = with(density) { blur.toPx() }
    var origin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .graphicsLayer()
            .clip(shape)
    ) {
        val image = backdrop?.image
        if (image != null) {
            val scale = backdrop.scale
            Canvas(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        clip = true
                        this.shape = shape
                        renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                    }
            ) {
                val bmpW = image.width.toFloat()
                val bmpH = image.height.toFloat()
                val srcX = (origin.x * scale).coerceIn(0f, (bmpW - 1f).coerceAtLeast(0f))
                val srcY = (origin.y * scale).coerceIn(0f, (bmpH - 1f).coerceAtLeast(0f))
                val srcW = (size.width * scale).coerceIn(1f, bmpW - srcX)
                val srcH = (size.height * scale).coerceIn(1f, bmpH - srcY)
                drawImage(
                    image = image,
                    srcOffset = IntOffset(srcX.roundToInt(), srcY.roundToInt()),
                    srcSize = IntSize(srcW.roundToInt().coerceAtLeast(1), srcH.roundToInt().coerceAtLeast(1)),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Low,
                )
            }
        } else {
            Box(
                Modifier.matchParentSize().background(
                    Brush.linearGradient(listOf(Color(0xFF1A2340), Color(0xFF101827), Color(0xFF0B1A1E)))
                )
            )
        }

        // single translucent wash, nothing else — pure frosted blur
        Box(Modifier.matchParentSize().background(Color.White.copy(alpha = 0.05f)))
        Box(Modifier.matchParentSize().background(tint.copy(alpha = 0.08f)))

        content()
    }
}

@Composable
fun FrostedSurface(
    modifier: Modifier = Modifier,
    corner: Dp = 24.dp,
    tint: Color = Color(0xFF0D1621),
    highlight: Float = 0.08f,
    content: @Composable BoxScope.() -> Unit,
) {
    val brush = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = highlight),
            Color.White.copy(alpha = highlight * 0.35f),
            Color.Black.copy(alpha = 0.18f),
        )
    )
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(tint.copy(alpha = 0.56f))
            .background(brush)
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        Color(0xFF9FF4FF).copy(alpha = 0.18f),
                        Color.White.copy(alpha = 0.05f),
                        Color.White.copy(alpha = 0.09f),
                    )
                ),
                RoundedCornerShape(corner),
            ),
        content = content,
    )
}

@Composable
fun GlassChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = androidx.compose.animation.core.tween(120),
        label = "chipPress",
    )
    val labelScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 600f),
        label = "chipScale",
    )
    val textColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) Color(0xFFEAFDFF) else Color.White.copy(alpha = 0.55f),
        animationSpec = androidx.compose.animation.core.tween(220),
        label = "chipText",
    )
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .then(
                if (onBounds != null) {
                    Modifier.onGloballyPositioned {
                        val p = it.positionInRoot()
                        onBounds(
                            androidx.compose.ui.geometry.Rect(
                                p.x, p.y, p.x + it.size.width, p.y + it.size.height
                            )
                        )
                    }
                } else Modifier
            )
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            letterSpacing = 0.3.sp,
            modifier = Modifier.graphicsLayer {
                scaleX = labelScale
                scaleY = labelScale
            },
        )
    }
}
