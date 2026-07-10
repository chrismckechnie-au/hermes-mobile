package au.com.chrismckechnie.hermesmobile

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Desktop dashboard atmosphere: teal gradient + warm amber glow + film-grain
 * noise. Drawn by a background-only sibling under all content:
 * `graphicsLayer()` gives it its own RenderNode (content redraws — e.g.
 * streaming chat — never re-execute this draw), `drawWithCache` allocates
 * the brushes once per size change.
 *
 * Grain needs AGSL (API 33+); API 26-32 get gradient + glow only.
 */
fun Modifier.hermesBackdrop(palette: HermesPalette): Modifier = this
    .graphicsLayer()
    .drawWithCache {
        val ground = Brush.verticalGradient(
            0f to palette.AbyssTop,
            1f to palette.Abyss,
        )
        // Warm glow: desktop's rgba(255,189,56,0.35), centered high but
        // faded out inside the top status-bar band so system-bar icons
        // always sit on plain canvas.
        val glow = Brush.radialGradient(
            colors = listOf(palette.WarmGlow, Color.Transparent),
            center = Offset(size.width * 0.5f, size.height * 0.18f),
            radius = (size.width * 0.95f).coerceAtLeast(1f),
        )
        val topScrimEnd = 40.dp.toPx()
        val topScrim = Brush.verticalGradient(
            0f to palette.Abyss.copy(alpha = 0.85f),
            1f to Color.Transparent,
            endY = topScrimEnd,
        )
        val grain = if (Build.VERSION.SDK_INT >= 33) grainBrush() else null
        onDrawBehind {
            drawRect(ground)
            drawRect(glow)
            drawRect(topScrim)
            grain?.let { drawRect(it, alpha = palette.GrainAlpha) }
        }
    }

/** Static hash-noise grain; coordinate-driven, no uniforms, no per-frame work. */
@RequiresApi(33)
private fun grainBrush(): ShaderBrush = ShaderBrush(
    RuntimeShader(
        """
        half4 main(float2 co) {
            float n = fract(sin(dot(co, float2(12.9898, 78.233))) * 43758.5453);
            return half4(half3(n), 1.0);
        }
        """.trimIndent(),
    ),
)
