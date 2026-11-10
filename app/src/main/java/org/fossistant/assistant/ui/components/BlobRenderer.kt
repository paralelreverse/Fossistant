package org.fossistant.assistant.ui.components

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The four theme colors the blob is painted with (ARGB ints). */
data class BlobPalette(val primary: Int, val secondary: Int, val tertiary: Int, val core: Int)

/**
 * Motion of the blob, advanced once per display frame. Shared by the in-app blob ([CosmicAiBlob]) and the
 * floating Agent Mode bubble ([FloatingAgentBlobView]) so both move identically.
 *
 * - IDLE: breathes slowly and turns slowly.
 * - AI_REASONING: turns faster at a fixed size, with a light sweeping around the rim.
 * - USER_TALKING: stops turning and swells with the microphone level; ripples flow *in* (listening).
 * - AI_TALKING: stops turning and swells with the voice; ripples flow *out* (speaking).
 *
 * Every value eases toward its target, so switching states never pops.
 */
class BlobPhysics {

    // Inputs (marked @Volatile so FloatingAgentBlobView.updateState can safely be called from any thread)
    @Volatile var state: BlobActivityState = BlobActivityState.IDLE
    @Volatile var micLevel: Float = 0f
    @Volatile var ttsLevel: Float = 0f
    @Volatile var showRings: Boolean = false
    @Volatile var reducedMotion: Boolean = false

    // Outputs read by the renderer
    var time = 0f; private set
    var audio = 0f; private set
    var scale = 1f; private set
    var rotationDeg = 0f; private set
    var morphPhase = 0f; private set
    var distortion = 0.10f; private set
    var ringFlow = 0f; private set
    var ringWeight = 0f; private set
    var reasoningWeight = 0f; private set
    var talkingWeight = 0f; private set

    private var idleWeight = 1f
    private var angularVelocity = 14f
    private var ringDirection = 1f
    private var typingImpulse = 0f

    /** A short swell when the user types a character. */
    fun kickTyping() {
        typingImpulse = 0.6f
    }

    fun step(rawDt: Float) {
        val dt = rawDt.coerceIn(0.001f, 0.05f)
        val motion = if (reducedMotion) 0.3f else 1f
        time += dt

        val talking = state == BlobActivityState.USER_TALKING || state == BlobActivityState.AI_TALKING
        typingImpulse = (typingImpulse - dt * 2.5f).coerceAtLeast(0f)

        // Audio envelope: quick attack, slower release, like a VU meter.
        val target = when (state) {
            BlobActivityState.USER_TALKING -> maxOf(micLevel, typingImpulse)
            // TTS level can be missing on some voices: fall back to a gentle speaking cadence.
            BlobActivityState.AI_TALKING -> if (ttsLevel > 0.03f) ttsLevel else 0.28f + 0.12f * sin(time * 7.3f)
            else -> 0f
        }.coerceIn(0f, 1f)
        audio = approach(audio, target, if (target > audio) 22f else 7f, dt)

        idleWeight = approach(idleWeight, if (state == BlobActivityState.IDLE) 1f else 0f, 6f, dt)
        talkingWeight = approach(talkingWeight, if (talking) 1f else 0f, 6f, dt)
        reasoningWeight = approach(reasoningWeight, if (state == BlobActivityState.AI_REASONING) 1f else 0f, 5f, dt)
        ringWeight = approach(ringWeight, if (showRings || talking) 1f else 0f, 4f, dt)

        val targetVelocity = when (state) {
            BlobActivityState.IDLE -> 14f
            BlobActivityState.AI_REASONING -> 72f
            else -> 0f
        }
        angularVelocity = approach(angularVelocity, targetVelocity, 3.5f, dt)
        rotationDeg = (rotationDeg + angularVelocity * dt * motion) % 360f

        val targetDistortion = when (state) {
            BlobActivityState.IDLE -> 0.095f
            BlobActivityState.AI_REASONING -> 0.07f
            else -> 0.075f + 0.15f * audio
        }
        distortion = approach(distortion, targetDistortion, 6f, dt)

        val morphSpeed = when (state) {
            BlobActivityState.IDLE -> 0.55f
            BlobActivityState.AI_REASONING -> 0.35f
            else -> 0.75f + 2.4f * audio
        }
        morphPhase += dt * morphSpeed * motion

        ringDirection = approach(ringDirection, if (state == BlobActivityState.USER_TALKING) -1f else 1f, 3f, dt)
        ringFlow += dt * (0.18f + 0.85f * audio) * ringDirection * motion

        val breath = sin(time * 1.5f) * 0.045f * motion
        val swell = audio * 0.2f + 0.035f * sin(morphPhase * 3.1f) * audio
        scale = (1f + idleWeight * breath + talkingWeight * swell + typingImpulse * 0.08f).coerceIn(0.9f, 1.3f)
    }

    private fun approach(current: Float, target: Float, rate: Float, dt: Float): Float =
        current + (target - current) * (1f - exp(-rate * dt))
}

/**
 * Paints the biomorphic blob with Android's Canvas: a soft glow, thin wavy ripples, translucent jelly bubbles
 * with bright (fresnel) edges, a dark nebula core with teal and violet clouds and twinkling stars, glass lobes
 * on top, and one soft specular highlight. Outlines are smooth Catmull-Rom curves. Shaders are built once per
 * palette and only re-positioned each frame.
 */
class BlobRenderer {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val matrix = Matrix()
    private val px = FloatArray(POINTS)
    private val py = FloatArray(POINTS)

    private var palette: BlobPalette? = null
    private lateinit var glowShader: Shader
    private lateinit var bubbleShader: Shader
    private lateinit var coreShader: Shader
    private lateinit var fresnelShader: Shader
    private lateinit var cloudA: Shader
    private lateinit var cloudB: Shader
    private lateinit var cloudC: Shader
    private lateinit var specShader: Shader
    private lateinit var sweepShader: Shader
    private val starColors = IntArray(4)

    private val starRadius = FloatArray(STARS) { i -> sqrt((i + 1f) / (STARS + 2f)) * 0.78f }
    private val starAngle = FloatArray(STARS) { i -> i * 2.3999632f }
    private val starPhase = FloatArray(STARS) { i -> i * 0.77f }

    fun setPalette(p: BlobPalette) {
        if (p == palette) return
        palette = p
        val clear = Color.TRANSPARENT
        glowShader = radial(intArrayOf(alpha(p.primary, 140), alpha(p.tertiary, 60), clear), floatArrayOf(0f, 0.5f, 1f))
        bubbleShader = radial(
            intArrayOf(alpha(p.tertiary, 45), alpha(p.secondary, 60), alpha(p.primary, 160), alpha(Color.WHITE, 130), clear),
            floatArrayOf(0f, 0.5f, 0.82f, 0.94f, 1f)
        )
        coreShader = radial(
            intArrayOf(0xFF060914.toInt(), p.core, alpha(p.tertiary, 150), alpha(p.primary, 210)),
            floatArrayOf(0f, 0.62f, 0.9f, 1f)
        )
        fresnelShader = radial(
            intArrayOf(clear, clear, alpha(Color.WHITE, 95), alpha(Color.WHITE, 0)),
            floatArrayOf(0f, 0.7f, 0.93f, 1f)
        )
        cloudA = radial(intArrayOf(alpha(p.secondary, 150), alpha(p.secondary, 40), clear), floatArrayOf(0f, 0.5f, 1f))
        cloudB = radial(intArrayOf(alpha(p.primary, 140), alpha(p.primary, 30), clear), floatArrayOf(0f, 0.55f, 1f))
        cloudC = radial(intArrayOf(alpha(p.tertiary, 110), clear), floatArrayOf(0f, 1f))
        specShader = radial(intArrayOf(alpha(Color.WHITE, 150), alpha(Color.WHITE, 45), clear), floatArrayOf(0f, 0.45f, 1f))
        sweepShader = SweepGradient(
            0f, 0f,
            intArrayOf(clear, clear, alpha(p.secondary, 210), alpha(Color.WHITE, 235), clear),
            floatArrayOf(0f, 0.55f, 0.86f, 0.94f, 1f)
        )
        starColors[0] = Color.WHITE
        starColors[1] = p.secondary
        starColors[2] = p.primary
        starColors[3] = lighten(p.tertiary)
    }

    /**
     * Draws the blob centered in a [width] x [height] area. [baseRatio] is the body radius as a fraction of
     * the smaller side (before audio swell); [density] scales strokes and stars.
     */
    fun draw(canvas: Canvas, width: Float, height: Float, ph: BlobPhysics, baseRatio: Float, density: Float) {
        if (palette == null) return
        val cx = width / 2f
        val cy = height / 2f
        val half = min(width, height) / 2f
        val r = half * 2f * baseRatio * ph.scale
        val rot = ph.rotationDeg * DEG
        val audio = ph.audio
        val hairline = (density * 1.1f).coerceAtLeast(1f)

        // 1. Soft ambient glow
        drawShaded(canvas, glowShader, cx, cy, min(r * (1.75f + 0.3f * audio), half), (190 + 60 * audio).toInt())
        // A tighter bloom hugging the body
        drawShaded(canvas, glowShader, cx, cy, r * (1.3f + 0.15f * audio), (150 + 70 * audio).toInt())

        // 2. Ripples: thin wavy rings drifting outward (speaking) or inward (listening)
        if (ph.ringWeight > 0.01f) {
            val inner = r * 1.12f
            val outer = maxOf(inner * 1.05f, half * 0.97f)
            stroke.shader = null
            stroke.strokeWidth = hairline * (1f + 0.6f * audio)
            val flow = ph.ringFlow - floor(ph.ringFlow)
            for (i in 0 until RINGS) {
                val pos = (i + flow) / RINGS
                val ringR = inner + (outer - inner) * pos
                val fadeIn = (pos / 0.12f).coerceAtMost(1f)
                val strength = ph.ringWeight * fadeIn * (1f - pos).pow(1.4f) * (0.45f + 0.55f * audio)
                stroke.color = alpha(mix(palette!!.secondary, palette!!.primary, pos), (strength * 240).toInt())
                // Neighbouring rings share nearly the same wave, so they stay parallel instead of crossing.
                organicPath(cx, cy, ringR, ph.morphPhase * 0.9f + i * 0.25f, 5, 0.012f + 0.035f * audio)
                canvas.drawPath(path, stroke)
            }
        }

        // 3. Translucent jelly bubbles behind the body
        for (i in 0 until 3) {
            val angle = rot * 0.6f + i * (2f * PI.toFloat() / 3f) + 0.4f
            val lx = cx + cos(angle) * r * 0.3f
            val ly = cy + sin(angle) * r * 0.3f
            val lr = r * (0.72f - 0.04f * i)
            organicPath(lx, ly, lr, ph.morphPhase + i * 2.1f, 3, ph.distortion)
            drawShadedPath(canvas, bubbleShader, lx, ly, lr * 1.12f, 215)
        }

        // 4. Body: dark nebula with drifting clouds, stars and a bright edge
        organicPath(cx, cy, r, ph.morphPhase * 0.85f + 0.6f, 3, ph.distortion * 0.8f)
        drawShadedPath(canvas, coreShader, cx, cy, r * 1.08f, 235)
        canvas.save()
        canvas.clipPath(path)
        val drift = rot * 0.5f + ph.time * 0.12f
        drawShaded(canvas, cloudA, cx + cos(drift) * r * 0.32f, cy + sin(drift) * r * 0.22f + r * 0.18f, r * 0.75f, 255)
        drawShaded(canvas, cloudB, cx + cos(drift + 2.4f) * r * 0.38f, cy + sin(drift + 2.4f) * r * 0.3f - r * 0.1f, r * 0.7f, 230)
        drawShaded(canvas, cloudC, cx + cos(drift + 4.3f) * r * 0.3f, cy + sin(drift + 4.3f) * r * 0.35f, r * 0.55f, 200)
        fill.shader = null
        val starScale = (half / 110f).coerceIn(0.35f, 2.2f) * density.coerceAtMost(3f) / 2.6f
        for (i in 0 until STARS) {
            val a = starAngle[i] + rot * 0.35f
            val sr = starRadius[i] * r
            val twinkle = 0.5f + 0.5f * sin(ph.time * (1.1f + (i % 5) * 0.25f) + starPhase[i])
            fill.color = alpha(starColors[i % 4], (60 + 195 * twinkle * twinkle).toInt())
            canvas.drawCircle(cx + cos(a) * sr, cy + sin(a) * sr, (if (i % 6 == 0) 1.5f else 0.8f) * starScale * (0.8f + 0.4f * twinkle), fill)
        }
        drawShaded(canvas, fresnelShader, cx, cy, r * 1.16f, 255)
        drawShaded(canvas, specShader, cx - r * 0.36f, cy - r * 0.44f, r * 0.42f, 200)
        canvas.restore()
        stroke.shader = null
        stroke.strokeWidth = hairline
        stroke.color = alpha(Color.WHITE, 70)
        canvas.drawPath(path, stroke)

        // 5. Glass lobes on top (screen-blended, so they brighten what's under them)
        fill.blendMode = BlendMode.SCREEN
        for (i in 0 until 2) {
            val angle = -rot * 0.45f + i * PI.toFloat() + 0.9f
            val lx = cx + cos(angle) * r * 0.24f
            val ly = cy + sin(angle) * r * 0.2f
            val lr = r * (0.6f - 0.08f * i)
            organicPath(lx, ly, lr, ph.morphPhase * 1.1f + 3.3f + i * 1.9f, 3, ph.distortion * 1.4f)
            drawShadedPath(canvas, bubbleShader, lx, ly, lr * 1.12f, 130)
            stroke.color = alpha(Color.WHITE, 45)
            canvas.drawPath(path, stroke)
        }
        fill.blendMode = null

        // 6. A small sharp glint, the "wet" highlight
        fill.shader = null
        fill.color = alpha(Color.WHITE, 170)
        canvas.drawCircle(cx - r * 0.4f, cy - r * 0.48f, r * 0.035f, fill)

        // 7. Thinking: a light sweeps around the rim
        if (ph.reasoningWeight > 0.01f) {
            organicPath(cx, cy, r * 1.03f, ph.morphPhase * 0.85f + 0.6f, 3, ph.distortion * 0.8f)
            matrix.setRotate(ph.time * 220f)
            matrix.postTranslate(cx, cy)
            sweepShader.setLocalMatrix(matrix)
            stroke.shader = sweepShader
            stroke.strokeWidth = hairline * 2.2f
            stroke.alpha = (ph.reasoningWeight * 255).toInt()
            canvas.drawPath(path, stroke)
            stroke.shader = null
        }
    }

    private fun drawShaded(canvas: Canvas, shader: Shader, x: Float, y: Float, radius: Float, alpha: Int) {
        matrix.setScale(radius, radius)
        matrix.postTranslate(x, y)
        shader.setLocalMatrix(matrix)
        fill.shader = shader
        fill.alpha = alpha.coerceIn(0, 255)
        canvas.drawCircle(x, y, radius, fill)
    }

    private fun drawShadedPath(canvas: Canvas, shader: Shader, x: Float, y: Float, radius: Float, alpha: Int) {
        matrix.setScale(radius, radius)
        matrix.postTranslate(x, y)
        shader.setLocalMatrix(matrix)
        fill.shader = shader
        fill.alpha = alpha.coerceIn(0, 255)
        canvas.drawPath(path, fill)
    }

    /** Fills [path] with a smooth closed organic outline (harmonic radius, Catmull-Rom curves). */
    private fun organicPath(cx: Float, cy: Float, radius: Float, phase: Float, lobes: Int, distortion: Float) {
        val step = 2f * PI.toFloat() / POINTS
        for (i in 0 until POINTS) {
            val t = i * step
            val wave = sin(t * lobes + phase) + 0.55f * cos(t * (lobes + 2) - phase * 1.3f) + 0.35f * sin(t * 2f + phase * 0.7f)
            val rr = radius * (1f + distortion * wave)
            px[i] = cx + cos(t) * rr
            py[i] = cy + sin(t) * rr
        }
        path.reset()
        path.moveTo(px[0], py[0])
        for (i in 0 until POINTS) {
            val i0 = (i - 1 + POINTS) % POINTS
            val i2 = (i + 1) % POINTS
            val i3 = (i + 2) % POINTS
            path.cubicTo(
                px[i] + (px[i2] - px[i0]) / 6f, py[i] + (py[i2] - py[i0]) / 6f,
                px[i2] - (px[i3] - px[i]) / 6f, py[i2] - (py[i3] - py[i]) / 6f,
                px[i2], py[i2]
            )
        }
        path.close()
    }

    private companion object {
        const val POINTS = 24
        const val RINGS = 7
        const val STARS = 34
        const val DEG = (PI / 180.0).toFloat()

        fun radial(colors: IntArray, stops: FloatArray): Shader = RadialGradient(0f, 0f, 1f, colors, stops, Shader.TileMode.CLAMP)

        fun alpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

        fun mix(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            return Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt()
            )
        }

        fun lighten(color: Int): Int = mix(color, Color.WHITE, 0.45f)
    }
}
