package nl.giejay.android.tv.immich.weather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Canvas-based weather animation drawn behind the weather page, one scene per condition:
 *
 * - CLEAR: a sun at the top of the sky with slowly turning light rays and a few wisps of cloud.
 * - PARTLY: a softer sun behind drifting clouds.
 * - CLEAR_NIGHT: twinkling stars, a glowing moon and the odd shooting star.
 * - CLOUDS: three parallax layers of soft clouds.
 * - RAIN / THUNDER: a dark cloud ceiling, rain in two depth layers with splashes at the bottom;
 *   thunder adds forked lightning with a double-flicker flash.
 * - SNOW: flakes in three depths, the nearest as large out-of-focus bokeh.
 * - FOG: wide, soft fog banks drifting in opposite directions and slowly thickening/thinning.
 *
 * Built for the frame's RK3128 GPU and Android 4.4: every soft shape (cloud, fog bank, bokeh dot)
 * is blurred once into a bitmap and then just drawn as a textured quad each frame; everything else
 * is plain lines/circles/paths, at a capped ~30fps, and nothing runs while the view isn't visible.
 * Only API 19 Canvas overloads are used (e.g. drawOval/drawRoundRect take a RectF). Turned off via
 * the WEATHER_ANIMATED_BACKGROUND preference (scene NONE).
 */
class WeatherAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Scene { NONE, CLEAR, PARTLY, CLEAR_NIGHT, CLOUDS, RAIN, SNOW, FOG, THUNDER }

    private val rnd = Random()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val locOnScreen = IntArray(2)
    private var scene = Scene.NONE
    private var running = false
    private var lastFrameMs = 0L
    /** Seconds of animation since the scene was built; drives all the slow oscillations. */
    private var time = 0f
    /** The view's current x on screen (non-zero while the sidebar is open). */
    private var offsetX = 0f

    // ~30fps, driven by a plain Handler post. Capped (rather than running at the display refresh via
    // postInvalidateOnAnimation) to keep the full-screen redraw light on the frame's RK3128 GPU.
    private val frameIntervalMs = 32L
    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, frameIntervalMs)
        }
    }

    private class Sprite(
        var x: Float, val y: Float, val w: Float, val h: Float, val speed: Float, val alpha: Int,
        val bitmap: Bitmap, var phase: Float, val bobAmp: Float, val breathe: Boolean
    )
    private class Drop(var x: Float, var y: Float, val len: Float, val speed: Float, val alpha: Int,
                       val width: Float, var groundY: Float, val near: Boolean)
    private class Ripple(val x: Float, val y: Float, var t: Float)
    private class Flake(var x: Float, var y: Float, val r: Float, val speed: Float, val amp: Float,
                        var phase: Float, val phaseSpeed: Float, val alpha: Int, val layer: Int)
    private class Star(val x: Float, val y: Float, val r: Float, val base: Float, val speed: Float,
                       val phase: Float, val bright: Boolean)

    private val sprites = ArrayList<Sprite>()   // clouds and fog banks, far → near
    private val drops = ArrayList<Drop>()
    private val ripples = ArrayList<Ripple>()
    private val flakes = ArrayList<Flake>()
    private val stars = ArrayList<Star>()
    private var glowShader: RadialGradient? = null

    // Sun (CLEAR / PARTLY)
    private var sunX = 0f
    private var sunY = 0f
    private var sunStrength = 0f
    private var sunHaloRadius = 0f
    private val rayPath = Path()
    private var rayShader: RadialGradient? = null
    private var sunHalo: RadialGradient? = null
    private var sunCore: RadialGradient? = null

    // Moon + shooting star (CLEAR_NIGHT)
    private var moonX = 0f
    private var moonY = 0f
    private var moonGlow: RadialGradient? = null
    private var shootT = -1f
    private var nextShoot = 0f
    private var shootX = 0f
    private var shootY = 0f

    // Lightning (THUNDER)
    private val boltPath = Path()
    private var boltT = -1f
    private var nextStrike = 0f

    // Pre-rendered soft sprites, built lazily the first time a scene needs them.
    private var lightClouds: List<Bitmap>? = null
    private var stormClouds: List<Bitmap>? = null
    private var fogBank: Bitmap? = null
    private var softDot: Bitmap? = null

    private val density get() = resources.displayMetrics.density

    fun setScene(newScene: Scene) {
        if (newScene != scene) {
            scene = newScene
            build()
        }
        if (scene == Scene.NONE) stop() else ensureRunning()
        invalidate()
    }

    // ---------------------------------------------------------------------------------------------
    // Scene setup

    private fun build() {
        sprites.clear(); drops.clear(); ripples.clear(); flakes.clear(); stars.clear()
        glowShader = null; rayShader = null; sunHalo = null; sunCore = null; moonGlow = null
        sunStrength = 0f; boltT = -1f; shootT = -1f; time = 0f
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val d = density

        // The view sits behind the (collapsible) sidebar and slides with it, so the sun/moon/glow are
        // placed in screen coordinates and shifted back by the view's current offset when drawn.
        val screenW = resources.displayMetrics.widthPixels.toFloat()
        fun screenX(f: Float) = screenW * f
        val skyX = screenX(0.5f)

        when (scene) {
            Scene.CLEAR -> {
                setupSun(screenX(0.40f), -h * 0.02f, 1f, h, d)
                cloudLayer(lightClouds(), 3, 0.30f, 0.06f, 0.34f, 5f, 40)
            }
            Scene.PARTLY -> {
                setupSun(screenX(0.40f), -h * 0.02f, 0.75f, h, d)
                cloudLayer(lightClouds(), 3, 0.34f, 0.04f, 0.30f, 4f, 95)
                cloudLayer(lightClouds(), 3, 0.50f, 0.24f, 0.52f, 8f, 130)
                cloudLayer(lightClouds(), 2, 0.80f, 0.72f, 0.95f, 14f, 70)
            }
            Scene.CLEAR_NIGHT -> setupNight(w, h, d, screenX(0.40f))
            Scene.CLOUDS -> {
                glowShader = skyGlow(skyX, h, 0x40EAF1F7, 0x1AEAF1F7)
                cloudLayer(lightClouds(), 4, 0.34f, 0.04f, 0.30f, 4f, 95)
                cloudLayer(lightClouds(), 3, 0.52f, 0.22f, 0.50f, 8f, 125)
                cloudLayer(lightClouds(), 2, 0.80f, 0.70f, 0.95f, 14f, 80)
            }
            Scene.RAIN, Scene.THUNDER -> {
                val storm = scene == Scene.THUNDER
                glowShader = if (storm) skyGlow(skyX, h, 0x22C8D2E0, 0x0EC8D2E0)
                else skyGlow(skyX, h, 0x2EDCE6F0, 0x12DCE6F0)
                cloudLayer(stormClouds(), 4, 0.42f, -0.04f, 0.16f, 5f, if (storm) 190 else 170)
                cloudLayer(stormClouds(), 3, 0.62f, 0.04f, 0.26f, 10f, if (storm) 220 else 190)
                val scale = if (storm) 1.3f else 1f
                repeat((100 * scale).toInt()) { drops.add(newDrop(w, h, d, near = false, anywhere = true)) }
                repeat((55 * scale).toInt()) { drops.add(newDrop(w, h, d, near = true, anywhere = true)) }
                nextStrike = 1.2f + rnd.nextFloat() * 2f
            }
            Scene.SNOW -> {
                glowShader = skyGlow(skyX, h, 0x44E8F0F8, 0x1CE8F0F8)
                cloudLayer(lightClouds(), 3, 0.36f, 0.02f, 0.24f, 4f, 70)
                repeat(70) { flakes.add(newFlake(w, h, d, 0, anywhere = true)) }
                repeat(45) { flakes.add(newFlake(w, h, d, 1, anywhere = true)) }
                repeat(10) { flakes.add(newFlake(w, h, d, 2, anywhere = true)) }
            }
            Scene.FOG -> {
                glowShader = skyGlow(skyX, h, 0x3CE6E9ED, 0x18E6E9ED)
                setupFog(w, h, d)
            }
            Scene.NONE -> {}
        }
    }

    /** Broad, soft diffuse light from above (for non-clear skies). */
    private fun skyGlow(cx: Float, h: Float, center: Int, mid: Int) = RadialGradient(
        cx, -h * 0.08f, h * 1.1f,
        intArrayOf(center, mid, 0x00000000),
        floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)

    /**
     * One parallax layer of [n] clouds spread evenly across the width (so the sky never clumps or
     * empties out), each jittered in size, height, speed and opacity.
     */
    private fun cloudLayer(bitmaps: List<Bitmap>, n: Int, widthFrac: Float, yFrom: Float, yTo: Float,
                           speed: Float, alpha: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = density
        repeat(n) { i ->
            val cw = w * widthFrac * (0.85f + rnd.nextFloat() * 0.3f)
            val ch = cw * CLOUD_H / CLOUD_W
            sprites.add(Sprite(
                x = (i + 0.2f + rnd.nextFloat() * 0.6f) / n * (w + cw) - cw,
                y = h * (yFrom + rnd.nextFloat() * (yTo - yFrom)) - ch / 2f,
                w = cw, h = ch,
                speed = speed * (0.85f + rnd.nextFloat() * 0.3f) * d,
                alpha = (alpha * (0.85f + rnd.nextFloat() * 0.3f)).toInt().coerceAtMost(255),
                bitmap = bitmaps[rnd.nextInt(bitmaps.size)],
                phase = rnd.nextFloat() * 6.28f,
                bobAmp = (2f + rnd.nextFloat() * 3f) * d,
                breathe = false
            ))
        }
    }

    /**
     * The sun sits just above the top edge between the two columns, so its rays fan down across the
     * page without washing out the location, temperature or the "Weather" title.
     */
    private fun setupSun(x: Float, y: Float, strength: Float, h: Float, d: Float) {
        sunX = x; sunY = y; sunStrength = strength
        val reach = h * 1.25f
        rayPath.reset()
        val n = 14
        for (i in 0 until n) {
            val a = Math.toRadians((i * 360.0 / n) + rnd.nextFloat() * 10.0)
            val half = Math.toRadians(1.2 + rnd.nextFloat() * 2.4)
            val len = reach * (0.65f + rnd.nextFloat() * 0.35f)
            rayPath.moveTo(0f, 0f)
            rayPath.lineTo((cos(a - half) * len).toFloat(), (sin(a - half) * len).toFloat())
            rayPath.lineTo((cos(a + half) * len).toFloat(), (sin(a + half) * len).toFloat())
            rayPath.close()
        }
        rayShader = RadialGradient(0f, 0f, reach,
            intArrayOf(0x3CFFF6DC, 0x12FFF0C8, 0x00FFF0C8), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        sunHaloRadius = h * 0.9f
        sunHalo = RadialGradient(x, y, sunHaloRadius,
            intArrayOf(0x70FFF3D6, 0x24FFE7B8, 0x00FFE7B8), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        sunCore = RadialGradient(x, y, 95f * d,
            intArrayOf(0xFFFFFFF6.toInt(), 0xB0FFF4D2.toInt(), 0x00FFF0C0), floatArrayOf(0f, 0.38f, 1f),
            Shader.TileMode.CLAMP)
    }

    private fun setupNight(w: Float, h: Float, d: Float, moonCenterX: Float) {
        repeat(130) {
            stars.add(Star(
                x = rnd.nextFloat() * w,
                // Denser toward the top of the sky, thinning out toward the horizon.
                y = h * 0.88f * rnd.nextFloat().pow(1.5f),
                r = (0.6f + rnd.nextFloat() * 1.2f) * d,
                base = 0.35f + rnd.nextFloat() * 0.65f,
                speed = 0.6f + rnd.nextFloat() * 2.2f,
                phase = rnd.nextFloat() * 6.28f,
                bright = false
            ))
        }
        repeat(7) {
            stars.add(Star(rnd.nextFloat() * w, h * 0.6f * rnd.nextFloat(), (1.6f + rnd.nextFloat()) * d,
                0.7f + rnd.nextFloat() * 0.3f, 0.4f + rnd.nextFloat(), rnd.nextFloat() * 6.28f, bright = true))
        }
        softDot()
        moonX = moonCenterX
        moonY = h * 0.14f
        moonGlow = RadialGradient(moonX, moonY, h * 0.65f,
            intArrayOf(0x3CC8D8FF, 0x10C8D8FF, 0x00C8D8FF), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        nextShoot = 3f + rnd.nextFloat() * 5f
    }

    /** Wide fog banks, thicker toward the ground, alternating drift direction. */
    private fun setupFog(w: Float, h: Float, d: Float) {
        val bank = fogBank()
        val n = 8
        repeat(n) { i ->
            val f = i / (n - 1f)
            val bw = w * (1.1f + rnd.nextFloat() * 0.5f)
            val bh = h * (0.22f + rnd.nextFloat() * 0.12f)
            sprites.add(Sprite(
                x = -bw * 0.3f + rnd.nextFloat() * (w - bw * 0.4f),
                y = h * (0.02f + 0.9f * f) - bh / 2f,
                w = bw, h = bh,
                speed = (5f + rnd.nextFloat() * 7f) * d * (if (i % 2 == 0) 1f else -1f),
                alpha = (45 + 55 * f).toInt(),
                bitmap = bank,
                phase = rnd.nextFloat() * 6.28f,
                bobAmp = (3f + rnd.nextFloat() * 4f) * d,
                breathe = true
            ))
        }
    }

    private fun newDrop(w: Float, h: Float, d: Float, near: Boolean, anywhere: Boolean): Drop {
        val len = if (near) (20f + rnd.nextFloat() * 12f) * d else (8f + rnd.nextFloat() * 6f) * d
        return Drop(
            x = rnd.nextFloat() * w * 1.2f - w * 0.1f,
            y = if (anywhere) rnd.nextFloat() * h else -len - rnd.nextFloat() * h * 0.1f,
            len = len,
            speed = if (near) (1100f + rnd.nextFloat() * 300f) * d else (650f + rnd.nextFloat() * 200f) * d,
            alpha = if (near) 90 + rnd.nextInt(60) else 35 + rnd.nextInt(40),
            width = if (near) 1.8f * d else 1f * d,
            groundY = h * (0.86f + rnd.nextFloat() * 0.13f),
            near = near
        )
    }

    private fun newFlake(w: Float, h: Float, d: Float, layer: Int, anywhere: Boolean): Flake {
        val r = when (layer) {
            0 -> 0.8f + rnd.nextFloat()
            1 -> 2.2f + rnd.nextFloat() * 1.4f
            else -> 9f + rnd.nextFloat() * 7f
        } * d
        return Flake(
            x = rnd.nextFloat() * w,
            y = if (anywhere) rnd.nextFloat() * h else -r * 2f,
            r = r,
            speed = when (layer) { 0 -> 18f + rnd.nextFloat() * 12f; 1 -> 35f + rnd.nextFloat() * 20f; else -> 65f + rnd.nextFloat() * 30f } * d,
            amp = when (layer) { 0 -> 6f + rnd.nextFloat() * 8f; 1 -> 10f + rnd.nextFloat() * 16f; else -> 20f + rnd.nextFloat() * 20f } * d,
            phase = rnd.nextFloat() * 6.28f,
            phaseSpeed = 0.5f + rnd.nextFloat() * 1.0f,
            alpha = when (layer) { 0 -> 110 + rnd.nextInt(50); 1 -> 190 + rnd.nextInt(45); else -> 70 + rnd.nextInt(40) },
            layer = layer
        )
    }

    /** A jagged bolt from the cloud ceiling down into the sky, with one or two forks. */
    private fun makeBolt(w: Float, h: Float, d: Float) {
        boltPath.reset()
        var x = w * (0.15f + rnd.nextFloat() * 0.7f)
        var y = h * 0.08f
        val endY = h * (0.55f + rnd.nextFloat() * 0.25f)
        boltPath.moveTo(x, y)
        val points = ArrayList<FloatArray>()
        while (y < endY) {
            y += (18f + rnd.nextFloat() * 18f) * d
            x += (rnd.nextFloat() - 0.5f) * 44f * d
            boltPath.lineTo(x, y)
            points.add(floatArrayOf(x, y))
        }
        repeat(1 + rnd.nextInt(2)) {
            val start = points[rnd.nextInt(max(1, points.size / 2))]
            var bx = start[0]
            var by = start[1]
            val dir = if (rnd.nextBoolean()) 1f else -1f
            boltPath.moveTo(bx, by)
            repeat(4 + rnd.nextInt(3)) {
                by += (12f + rnd.nextFloat() * 14f) * d
                bx += dir * (8f + rnd.nextFloat() * 18f) * d
                boltPath.lineTo(bx, by)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Sprites

    private fun lightClouds() = lightClouds
        ?: List(3) { makeCloudSprite(it * 7919L + 17L, 0xFFFFFFFF.toInt(), 0xFFA9B7C7.toInt()) }.also { lightClouds = it }

    private fun stormClouds() = stormClouds
        ?: List(3) { makeCloudSprite(it * 104729L + 3L, 0xFF97A3B2.toInt(), 0xFF3B4552.toInt()) }.also { stormClouds = it }

    /**
     * Renders one fluffy cloud into a bitmap once: a flat-bottomed base plus a row of billows,
     * shaded from [top] to a darker [bottom] underside, with a baked blur for soft edges.
     * BlurMaskFilter is fine here because this is a software canvas.
     */
    private fun makeCloudSprite(seed: Long, top: Int, bottom: Int): Bitmap {
        val bw = CLOUD_W
        val bh = CLOUD_H
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = Random(seed)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(22f, BlurMaskFilter.Blur.NORMAL)
            shader = LinearGradient(0f, bh * 0.2f, 0f, bh * 0.9f, top, bottom, Shader.TileMode.CLAMP)
        }
        val pad = 35f
        val baseBottom = bh - pad
        val inset = pad + 30f
        val shape = RectF(inset, baseBottom - 70f, bw - inset, baseBottom)
        c.drawRoundRect(shape, 35f, 35f, p)
        val n = 5 + r.nextInt(2)
        val usable = bw - 2 * inset
        for (i in 0 until n) {
            val t = (i + 0.5f) / n
            val cx = inset + usable * t + (r.nextFloat() - 0.5f) * 20f
            val bump = sin(t * PI).toFloat()
            val rad = 30f + 40f * bump + r.nextFloat() * 10f
            c.drawCircle(cx, baseBottom - 50f - rad * 0.55f, rad, p)
        }
        return bmp
    }

    /** A long, heavily blurred white ellipse — stretched across the screen as a fog bank. */
    private fun fogBank() = fogBank ?: run {
        val bmp = Bitmap.createBitmap(512, 128, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(bmp).drawOval(RectF(36f, 34f, 476f, 94f), p)
        bmp
    }.also { fogBank = it }

    /** A soft round glow (bright center fading to transparent), for bokeh snowflakes and star halos. */
    private fun softDot() = softDot ?: run {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(32f, 32f, 32f,
                intArrayOf(0xFFFFFFFF.toInt(), 0x80FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP)
        }
        Canvas(bmp).drawCircle(32f, 32f, 32f, p)
        bmp
    }.also { softDot = it }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        build()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureRunning()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) ensureRunning() else stop()
    }

    private fun ensureRunning() {
        if (!running && scene != Scene.NONE && windowVisibility == View.VISIBLE) {
            running = true
            lastFrameMs = 0L
            removeCallbacks(frameRunnable)
            postDelayed(frameRunnable, frameIntervalMs)
        }
    }

    private fun stop() {
        running = false
        removeCallbacks(frameRunnable)
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.05f)
        lastFrameMs = now
        time += dt
        val w = width.toFloat()
        val h = height.toFloat()
        getLocationOnScreen(locOnScreen)
        offsetX = locOnScreen[0].toFloat()
        when (scene) {
            Scene.CLEAR, Scene.PARTLY -> { drawSun(canvas, w, h); drawSprites(canvas, dt, w) }
            Scene.CLEAR_NIGHT -> drawNight(canvas, dt, w, h)
            Scene.CLOUDS, Scene.FOG -> { drawGlow(canvas, w, h); drawSprites(canvas, dt, w) }
            Scene.RAIN -> { drawGlow(canvas, w, h); drawSprites(canvas, dt, w); drawRain(canvas, dt, w, h) }
            Scene.THUNDER -> {
                drawGlow(canvas, w, h); drawSprites(canvas, dt, w); drawRain(canvas, dt, w, h)
                drawLightning(canvas, dt, w, h)
            }
            Scene.SNOW -> { drawGlow(canvas, w, h); drawSprites(canvas, dt, w); drawSnow(canvas, dt, w, h) }
            Scene.NONE -> {}
        }
    }

    /** Soft, slowly-breathing ambient light wash from above. */
    private fun drawGlow(canvas: Canvas, w: Float, h: Float) {
        val shader = glowShader ?: return
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.shader = shader
        paint.alpha = (255 * (0.9f + 0.1f * sin(time * 0.6f))).toInt()
        canvas.save()
        canvas.translate(-offsetX, 0f)
        canvas.drawRect(offsetX, 0f, offsetX + w, h, paint)
        canvas.restore()
        paint.shader = null
    }

    /** Clouds / fog banks: drift (wrapping around), bob gently, and (fog) thicken and thin. */
    private fun drawSprites(canvas: Canvas, dt: Float, w: Float) {
        for (s in sprites) {
            s.x += s.speed * dt
            if (s.speed >= 0f && s.x > w) s.x = -s.w
            if (s.speed < 0f && s.x + s.w < 0f) s.x = w
            s.phase += dt * 0.35f
            val y = s.y + sin(s.phase) * s.bobAmp
            rect.set(s.x, y, s.x + s.w, y + s.h)
            val a = if (s.breathe) s.alpha * (0.75f + 0.25f * sin(s.phase * 0.8f)) else s.alpha.toFloat()
            spritePaint.alpha = a.toInt().coerceIn(0, 255)
            canvas.drawBitmap(s.bitmap, null, rect, spritePaint)
        }
    }

    private fun drawSun(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.save()
        canvas.translate(-offsetX, 0f)
        // Warm halo, limited to its own bounds (and the view) rather than a full-screen fill.
        paint.shader = sunHalo
        paint.alpha = (255 * sunStrength * (0.92f + 0.08f * sin(time * 0.5f))).toInt().coerceIn(0, 255)
        canvas.drawRect(max(offsetX, sunX - sunHaloRadius), 0f, minOf(offsetX + w, sunX + sunHaloRadius),
            minOf(h, sunY + sunHaloRadius), paint)
        // Two sets of light rays turning slowly in opposite directions, so they shimmer as they cross.
        paint.shader = rayShader
        canvas.save()
        canvas.translate(sunX, sunY)
        canvas.rotate(time * 1.6f)
        paint.alpha = (255 * sunStrength * (0.75f + 0.25f * sin(time * 0.7f))).toInt().coerceIn(0, 255)
        canvas.drawPath(rayPath, paint)
        canvas.rotate(-time * 2.8f + 9f)
        canvas.scale(0.8f, 0.8f)
        paint.alpha = (150 * sunStrength * (0.75f + 0.25f * sin(time * 0.9f + 2f))).toInt().coerceIn(0, 255)
        canvas.drawPath(rayPath, paint)
        canvas.restore()
        paint.shader = sunCore
        paint.alpha = (255 * sunStrength).toInt().coerceIn(0, 255)
        canvas.drawCircle(sunX, sunY, 95f * density, paint)
        paint.shader = null
        canvas.restore()
    }

    private fun drawNight(canvas: Canvas, dt: Float, w: Float, h: Float) {
        val d = density
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.save()
        canvas.translate(-offsetX, 0f)
        moonGlow?.let {
            paint.shader = it
            canvas.drawRect(offsetX, 0f, offsetX + w, h, paint)
            paint.shader = null
        }
        canvas.restore()
        val dot = softDot()
        for (s in stars) {
            val twinkle = s.base * (0.55f + 0.45f * sin(time * s.speed + s.phase))
            if (s.bright) {
                val hr = s.r * 6f
                rect.set(s.x - hr, s.y - hr, s.x + hr, s.y + hr)
                spritePaint.alpha = (70 * twinkle).toInt()
                canvas.drawBitmap(dot, null, rect, spritePaint)
            }
            paint.color = Color.WHITE
            paint.alpha = (255 * twinkle).toInt().coerceIn(0, 255)
            canvas.drawCircle(s.x, s.y, s.r, paint)
        }

        // Moon: soft halo, disc, a few faint craters.
        canvas.save()
        canvas.translate(-offsetX, 0f)
        val mr = 30f * d
        rect.set(moonX - mr * 2.6f, moonY - mr * 2.6f, moonX + mr * 2.6f, moonY + mr * 2.6f)
        spritePaint.alpha = 90
        canvas.drawBitmap(dot, null, rect, spritePaint)
        paint.color = 0xFFF4F1E4.toInt()
        canvas.drawCircle(moonX, moonY, mr, paint)
        paint.color = 0xFFE2DDCB.toInt()
        canvas.drawCircle(moonX - mr * 0.32f, moonY - mr * 0.28f, mr * 0.22f, paint)
        canvas.drawCircle(moonX + mr * 0.36f, moonY + mr * 0.12f, mr * 0.16f, paint)
        canvas.drawCircle(moonX - mr * 0.08f, moonY + mr * 0.42f, mr * 0.12f, paint)
        canvas.restore()

        // Shooting star every few seconds: a short streak with a fading tail, down and to the left.
        if (shootT < 0f && time >= nextShoot) {
            shootT = 0f
            shootX = w * (0.35f + rnd.nextFloat() * 0.6f)
            shootY = h * (0.03f + rnd.nextFloat() * 0.25f)
        }
        if (shootT >= 0f) {
            shootT += dt
            val dur = 0.9f
            val p = shootT / dur
            if (p >= 1f) {
                shootT = -1f
                nextShoot = time + 6f + rnd.nextFloat() * 9f
            } else {
                val dx = -0.92f
                val dy = 0.38f
                val travel = 420f * d
                val hx = shootX + dx * travel * p
                val hy = shootY + dy * travel * p
                val tail = 130f * d * minOf(1f, p * 3f)
                val tx = hx - dx * tail
                val ty = hy - dy * tail
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 2f * d
                paint.color = Color.WHITE
                paint.shader = LinearGradient(tx, ty, hx, hy, 0x00FFFFFF, Color.WHITE, Shader.TileMode.CLAMP)
                paint.alpha = (255 * sin(p * PI).toFloat()).toInt().coerceIn(0, 255)
                canvas.drawLine(tx, ty, hx, hy, paint)
                paint.shader = null
                paint.style = Paint.Style.FILL
            }
        }
    }

    private fun drawRain(canvas: Canvas, dt: Float, w: Float, h: Float) {
        val d = density
        val slant = 0.10f
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.WHITE
        for (drop in drops) {
            drop.y += drop.speed * dt
            drop.x += drop.speed * slant * dt
            // Near drops "land" on the ground band at the bottom of the screen and splash.
            val landed = if (drop.near) drop.y >= drop.groundY else drop.y - drop.len > h
            if (landed) {
                if (drop.near && ripples.size < 40) ripples.add(Ripple(drop.x, drop.groundY, 0f))
                drop.y = -drop.len - rnd.nextFloat() * h * 0.1f
                drop.x = rnd.nextFloat() * w * 1.2f - w * 0.1f
                drop.groundY = h * (0.86f + rnd.nextFloat() * 0.13f)
            }
            paint.strokeWidth = drop.width
            paint.alpha = drop.alpha
            canvas.drawLine(drop.x, drop.y, drop.x - drop.len * slant, drop.y - drop.len, paint)
        }
        // Splash ripples: flat ellipses that widen and fade.
        paint.strokeWidth = 1.2f * d
        val iter = ripples.iterator()
        while (iter.hasNext()) {
            val r = iter.next()
            r.t += dt
            val p = r.t / 0.45f
            if (p >= 1f) { iter.remove(); continue }
            val rad = (3f + 16f * p) * d
            rect.set(r.x - rad, r.y - rad * 0.3f, r.x + rad, r.y + rad * 0.3f)
            paint.alpha = ((1f - p) * 80).toInt()
            canvas.drawOval(rect, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawLightning(canvas: Canvas, dt: Float, w: Float, h: Float) {
        val d = density
        if (boltT < 0f && time >= nextStrike) {
            makeBolt(w, h, d)
            boltT = 0f
            nextStrike = time + 4f + rnd.nextFloat() * 7f
        }
        if (boltT < 0f) return
        boltT += dt
        // Double flicker: strike, dip, re-strike, then a slower fade.
        val t = boltT
        val i = when {
            t < 0.06f -> 1f
            t < 0.12f -> 0.25f
            t < 0.20f -> 0.85f
            else -> max(0f, 0.85f * (1f - (t - 0.20f) / 0.5f))
        }
        if (t > 0.7f) { boltT = -1f; return }
        paint.style = Paint.Style.FILL
        paint.color = 0xFFDCE6FF.toInt()
        paint.alpha = (i * 70).toInt()
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFFC8D8FF.toInt()
        paint.strokeWidth = 9f * d
        paint.alpha = (i * 70).toInt()
        canvas.drawPath(boltPath, paint)
        paint.color = Color.WHITE
        paint.strokeWidth = 2.2f * d
        paint.alpha = (i * 255).toInt()
        canvas.drawPath(boltPath, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawSnow(canvas: Canvas, dt: Float, w: Float, h: Float) {
        val d = density
        val dot = softDot()
        // A slow, shared gust so the whole snowfall sways together; nearer flakes move more.
        val wind = sin(time * 0.15f) * 14f * d
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        for (f in flakes) {
            f.y += f.speed * dt
            f.x += wind * dt * (f.layer + 1) * 0.5f
            f.phase += f.phaseSpeed * dt
            if (f.y - f.r * 2f > h) {
                f.y = -f.r * 2f
                f.x = rnd.nextFloat() * w
            }
            if (f.x < -f.r * 2f) f.x += w + f.r * 4f
            if (f.x > w + f.r * 2f) f.x -= w + f.r * 4f
            val x = f.x + sin(f.phase) * f.amp
            if (f.layer == 2) {
                rect.set(x - f.r, f.y - f.r, x + f.r, f.y + f.r)
                spritePaint.alpha = f.alpha
                canvas.drawBitmap(dot, null, rect, spritePaint)
            } else {
                paint.alpha = f.alpha
                canvas.drawCircle(x, f.y, f.r, paint)
            }
        }
    }

    companion object {
        private const val CLOUD_W = 512
        private const val CLOUD_H = 256
    }
}
