package com.mohammed.istighfar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * برنامج الاستغفار
 * تطوير المبرمج: محمد عبدالله العمري
 */
class IstighfarView(context: Context) : View(context) {

    private class Drop(var y: Float, var vy: Float)
    private class Ripple(val start: Long)

    companion object {
        const val MAX = 100
        const val DEVELOPER = "تطوير المبرمج: محمد عبدالله العمري"
    }

    private val prefs = context.getSharedPreferences("istighfar", Context.MODE_PRIVATE)
    private var count = prefs.getInt("count", 0).coerceIn(0, MAX)
    private var shown = count.toFloat()
    private var last = SystemClock.uptimeMillis()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    // ---- random scenery (fixed seed so the scene is stable)
    private val rnd = Random(7)
    private val grassN = 170
    private val grassX = FloatArray(grassN) { rnd.nextFloat() }
    private val grassY = FloatArray(grassN) { rnd.nextFloat() }
    private val crackN = 36
    private val crackX = FloatArray(crackN) { rnd.nextFloat() }
    private val crackY = FloatArray(crackN) { rnd.nextFloat() }
    private val crackL = FloatArray(crackN) { 0.05f + rnd.nextFloat() * 0.12f }
    private val flowerN = 70
    private val flowerX = FloatArray(flowerN) { rnd.nextFloat() }
    private val flowerY = FloatArray(flowerN) { rnd.nextFloat() }
    private val flowerC = IntArray(flowerN) { rnd.nextInt(5) }
    private val rainN = 90
    private val rainX = FloatArray(rainN) { rnd.nextFloat() }
    private val rainP = FloatArray(rainN) { rnd.nextFloat() }
    private val rainS = FloatArray(rainN) { 0.8f + rnd.nextFloat() * 0.7f }
    private val flowerColors = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFF4C430.toInt(), 0xFFF08A3C.toInt(),
        0xFFEE7B96.toInt(), 0xFFA98BD6.toInt()
    )

    // trees: x, baseY, height (fractions of width/height)
    private val trees = arrayOf(
        floatArrayOf(0.08f, 0.50f, 0.17f),
        floatArrayOf(0.25f, 0.45f, 0.10f),
        floatArrayOf(0.40f, 0.43f, 0.06f),
        floatArrayOf(0.65f, 0.43f, 0.06f),
        floatArrayOf(0.80f, 0.455f, 0.11f),
        floatArrayOf(0.95f, 0.50f, 0.17f)
    )

    private val river = Path()
    private val riverSeg = Path()
    private val pm = PathMeasure()

    private val drops = ArrayList<Drop>()
    private val ripples = ArrayList<Ripple>()

    // verse card
    private val verses = mapOf(
        25 to arrayOf("يُرْسِلِ السَّمَاءَ عَلَيْكُم مِّدْرَارًا", "[نوح: 11]"),
        50 to arrayOf("وَيَجْعَل لَّكُمْ جَنَّاتٍ", "[نوح: 12]"),
        75 to arrayOf("وَيَجْعَل لَّكُمْ أَنْهَارًا", "[نوح: 12]"),
        100 to arrayOf("اسْتَغْفِرُوا رَبَّكُمْ إِنَّهُ كَانَ غَفَّارًا", "[نوح: 10]  •  اضغط للبدء من جديد")
    )
    private var cardV: Array<String>? = null
    private var cardStart = 0L

    // sound
    private val sounds: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()
    private val dropId = sounds.load(context, R.raw.drop, 1)
    private val srnd = Random()

    private val resetRun = Runnable {
        count = 0
        cardV = null
        drops.clear()
        save()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun save() {
        prefs.edit().putInt("count", count).apply()
    }

    fun release() {
        sounds.release()
    }

    // ---------------------------------------------------------------- helpers
    private fun cl(x: Float) = if (x < 0f) 0f else if (x > 1f) 1f else x

    private fun sm(x: Float, a: Float, b: Float): Float {
        val t = cl((x - a) / (b - a))
        return t * t * (3f - 2f * t)
    }

    private fun lerpC(a: Int, b: Int, t: Float): Int {
        val f = cl(t)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
        )
    }

    private fun fill(color: Int, alpha: Int = 255) {
        p.style = Paint.Style.FILL
        p.color = color
        p.alpha = alpha.coerceIn(0, 255)
    }

    private fun stroke(color: Int, width: Float, alpha: Int = 255) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = width
        p.color = color
        p.alpha = alpha.coerceIn(0, 255)
    }

    private fun arabic(n: Int): String =
        n.toString().map { '٠' + (it - '0') }.joinToString("")

    private fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.CENTER, alpha: Int = 255
    ) {
        tp.textSize = size
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.color = color
        tp.alpha = alpha.coerceIn(0, 255)
        tp.textAlign = align
        c.drawText(s, x, y, tp)
    }

    private fun fit(s: String, maxW: Float, size: Float): Float {
        tp.textSize = size
        tp.typeface = Typeface.DEFAULT_BOLD
        val m = tp.measureText(s)
        return if (m > maxW) size * maxW / m else size
    }

    // ---------------------------------------------------------------- layout
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fw = w.toFloat()
        val fh = h.toFloat()
        river.reset()
        river.moveTo(0.50f * fw, 0.56f * fh)
        river.cubicTo(0.36f * fw, 0.63f * fh, 0.50f * fw, 0.71f * fh, 0.40f * fw, 0.79f * fh)
        river.cubicTo(0.33f * fw, 0.85f * fh, 0.26f * fw, 0.90f * fh, 0.18f * fw, 1.00f * fh)
        pm.setPath(river, false)
    }

    // ---------------------------------------------------------------- input
    private fun tap() {
        if (count >= MAX) {
            count = 0
            cardV = null
            save()
            return
        }
        count++
        save()
        drops.add(Drop(height * 0.31f, height * 0.12f))
        verses[count]?.let {
            cardV = it
            cardStart = SystemClock.uptimeMillis()
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tap()
                postDelayed(resetRun, 2500) // hold 2.5s to reset the counter
            }
            MotionEvent.ACTION_POINTER_DOWN -> tap()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(resetRun)
        }
        return true
    }

    // ---------------------------------------------------------------- drawing
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        val dt = ((now - last) / 1000f).coerceIn(0f, 0.05f)
        last = now
        val t = now / 1000f
        shown += (count - shown) * min(1f, dt * 2.5f)
        val s = shown

        val gy = 0.41f * h
        val cx = 0.5f * w
        val cy = 0.525f * h
        val rx = 0.235f * w
        val ry = 0.042f * h

        val rainAmt = sm(s, 20f, 26f) * (1f - sm(s, 42f, 48f))

        // sky
        var sky = lerpC(0xFFCDB89A.toInt(), 0xFF8D97A3.toInt(), sm(s, 0f, 25f))
        sky = lerpC(sky, 0xFFA8C8E0.toInt(), sm(s, 40f, 50f))
        c.drawColor(sky)

        // clouds
        val cloudCol = lerpC(0xFFFFFFFF.toInt(), 0xFF6F7680.toInt(), rainAmt * 0.85f)
        fill(cloudCol)
        val drift = sin(t * 0.15f) * 0.012f * w
        cloud(c, 0.22f * w + drift, 0.335f * h, 0.07f * w)
        cloud(c, 0.62f * w - drift, 0.322f * h, 0.10f * w)
        cloud(c, 0.95f * w + drift, 0.355f * h, 0.06f * w)

        // mountains
        val mt = lerpC(0xFFB4917B.toInt(), 0xFF8E94B8.toInt(), sm(s, 0f, 50f))
        fill(mt)
        val mp = Path()
        mp.moveTo(0f, 0.43f * h)
        mp.lineTo(0f, 0.385f * h)
        mp.lineTo(0.15f * w, 0.345f * h)
        mp.lineTo(0.33f * w, 0.39f * h)
        mp.lineTo(0.52f * w, 0.35f * h)
        mp.lineTo(0.66f * w, 0.375f * h)
        mp.lineTo(0.83f * w, 0.34f * h)
        mp.lineTo(w, 0.385f * h)
        mp.lineTo(w, 0.43f * h)
        mp.close()
        c.drawPath(mp, p)

        // ground
        fill(lerpC(0xFFD9B27C.toInt(), 0xFF93C262.toInt(), sm(s, 0f, 35f)))
        c.drawRect(0f, gy, w, h, p)

        // green halo around the pond
        val haloA = (255 * (1f - sm(s, 5f, 35f))).toInt()
        if (haloA > 0) {
            rect.set(cx - rx * 1.45f, cy - ry * 2.4f, cx + rx * 1.45f, cy + ry * 2.6f)
            fill(0xFF7DC94E.toInt(), haloA)
            c.drawOval(rect, p)
        }

        // cracks (desert)
        val crackA = (150 * (1f - sm(s, 3f, 30f))).toInt()
        if (crackA > 0) {
            stroke(0xFF9C7A4B.toInt(), w * 0.004f, crackA)
            for (i in 0 until crackN) {
                val x = crackX[i] * w
                val y = gy + 0.04f * h + crackY[i] * (h - gy - 0.1f * h)
                val l = crackL[i] * w
                c.drawLine(x, y, x + l, y + l * 0.12f, p)
                c.drawLine(x + l * 0.5f, y + l * 0.06f, x + l * 0.62f, y + l * 0.2f, p)
            }
        }

        // grass
        val grassAmt = sm(s, 0f, 40f)
        val grassCol = lerpC(0xFFA08752.toInt(), 0xFF4F9A3C.toInt(), sm(s, 10f, 40f))
        val shownGrass = (grassN * (0.15f + 0.85f * grassAmt)).toInt()
        stroke(grassCol, w * 0.003f)
        for (i in 0 until shownGrass) {
            val y = gy + 0.02f * h + grassY[i] * (h - gy - 0.04f * h)
            val x = grassX[i] * w
            val sc = 0.5f + (y - gy) / (h - gy)
            val g = w * 0.013f * sc
            c.drawLine(x, y, x - g * 0.6f, y - g, p)
            c.drawLine(x, y, x, y - g * 1.2f, p)
            c.drawLine(x, y, x + g * 0.6f, y - g, p)
        }

        // river
        val riverAmt = sm(s, 20f, 60f)
        if (riverAmt > 0.01f) {
            riverSeg.reset()
            pm.getSegment(0f, pm.length * riverAmt, riverSeg, true)
            riverSeg.rLineTo(0f, 0f)
            stroke(0xFF4F93D0.toInt(), w * 0.075f)
            c.drawPath(riverSeg, p)
            stroke(0xFF6DAEE6.toInt(), w * 0.058f)
            c.drawPath(riverSeg, p)
            stroke(0xFFBFE0F8.toInt(), w * 0.006f, 160)
            c.drawPath(riverSeg, p)
        }

        // pond
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        fill(0xFF9B8D80.toInt())
        c.drawOval(rect, p)
        for (k in 0 until 46) {
            val a = (2.0 * PI * k / 46.0).toFloat()
            val sx = cx + cos(a) * rx * 0.94f
            val sy = cy + sin(a) * ry * 0.92f
            fill(if (k % 2 == 0) 0xFFB9AD9F.toInt() else 0xFF8E8176.toInt())
            rect.set(sx - w * 0.02f, sy - w * 0.012f, sx + w * 0.02f, sy + w * 0.012f)
            c.drawOval(rect, p)
        }
        rect.set(cx - rx * 0.88f, cy - ry * 0.80f, cx + rx * 0.88f, cy + ry * 0.80f)
        fill(0xFF4E3B2C.toInt())
        c.drawOval(rect, p)
        rect.set(cx - rx * 0.78f, cy - ry * 0.68f, cx + rx * 0.78f, cy + ry * 0.68f)
        fill(0xFF5AAEE6.toInt())
        c.drawOval(rect, p)
        rect.set(cx - rx * 0.55f, cy - ry * 0.5f, cx + rx * 0.2f, cy - ry * 0.05f)
        fill(0xFFFFFFFF.toInt(), 50)
        c.drawOval(rect, p)
        // ambient ripples
        for (k in 0..2) {
            val f = ((t * 0.25f + k / 3f) % 1f)
            rect.set(cx - rx * 0.6f * f, cy - ry * 0.55f * f, cx + rx * 0.6f * f, cy + ry * 0.55f * f)
            stroke(0xFFFFFFFF.toInt(), w * 0.0025f, (110 * (1f - f)).toInt())
            c.drawOval(rect, p)
        }

        // tap ripples
        ripples.removeAll { now - it.start > 1200 }
        for (r in ripples) {
            val age = (now - r.start) / 1200f
            val f = 0.1f + age * 0.9f
            rect.set(cx - rx * 0.74f * f, cy - ry * 0.66f * f, cx + rx * 0.74f * f, cy + ry * 0.66f * f)
            stroke(0xFFFFFFFF.toInt(), w * 0.004f, (230 * (1f - age)).toInt())
            c.drawOval(rect, p)
        }

        // trees
        val leaf = sm(s, 30f, 55f)
        for (tr in trees) {
            tree(c, tr[0] * w, tr[1] * h, tr[2] * h, leaf, w)
        }

        // flowers
        val flowerAmt = sm(s, 50f, 100f)
        val shownFlowers = (flowerN * flowerAmt).toInt()
        for (i in 0 until shownFlowers) {
            val y = gy + 0.05f * h + flowerY[i] * (h - gy - 0.1f * h)
            val x = flowerX[i] * w
            val sc = 0.5f + (y - gy) / (h - gy)
            fill(flowerColors[flowerC[i]])
            c.drawCircle(x, y, w * 0.009f * sc, p)
            fill(0xFFF7E27A.toInt())
            c.drawCircle(x, y, w * 0.003f * sc, p)
        }

        // rain
        if (rainAmt > 0.02f) {
            stroke(0xFFE8F0F8.toInt(), w * 0.003f, (150 * rainAmt).toInt())
            for (i in 0 until rainN) {
                val x = rainX[i] * w
                val y = ((t * rainS[i] + rainP[i]) % 1f) * h
                c.drawLine(x, y, x - w * 0.006f, y + h * 0.03f, p)
            }
        }

        // falling drops
        val dit = drops.iterator()
        while (dit.hasNext()) {
            val d = dit.next()
            d.vy += h * 3.2f * dt
            d.y += d.vy * dt
            if (d.y >= cy) {
                dit.remove()
                ripples.add(Ripple(now))
                sounds.play(dropId, 1f, 1f, 1, 0, 0.92f + srnd.nextFloat() * 0.2f)
            } else {
                drawDrop(c, cx, d.y, w * 0.022f)
            }
        }

        drawHud(c, w, h, now)
        postInvalidateOnAnimation()
    }

    private fun cloud(c: Canvas, x: Float, y: Float, r: Float) {
        c.drawCircle(x, y, r, p)
        c.drawCircle(x - r * 0.95f, y + r * 0.3f, r * 0.7f, p)
        c.drawCircle(x + r * 0.95f, y + r * 0.25f, r * 0.78f, p)
        rect.set(x - r * 1.5f, y + r * 0.2f, x + r * 1.6f, y + r * 1.0f)
        c.drawRoundRect(rect, r * 0.4f, r * 0.4f, p)
    }

    private fun tree(c: Canvas, x: Float, by: Float, th: Float, leaf: Float, w: Float) {
        val topX = x
        val topY = by - th
        val ends = arrayOf(
            floatArrayOf(x - 0.30f * th, by - 0.88f * th, 0.40f),
            floatArrayOf(x + 0.30f * th, by - 0.88f * th, 0.40f),
            floatArrayOf(x - 0.17f * th, by - 1.03f * th, 0.66f),
            floatArrayOf(x + 0.17f * th, by - 1.03f * th, 0.66f)
        )
        stroke(0xFF7A5A44.toInt(), th * 0.075f)
        c.drawLine(x, by, topX, topY, p)
        stroke(0xFF7A5A44.toInt(), th * 0.045f)
        for (e in ends) {
            c.drawLine(x, by - th * e[2], e[0], e[1], p)
        }
        if (leaf > 0.02f) {
            val r = th * 0.17f * leaf
            fill(0xFF6DBE4B.toInt())
            c.drawCircle(topX, topY - r * 0.3f, r * 1.1f, p)
            for (e in ends) c.drawCircle(e[0], e[1], r, p)
            fill(0xFF8FD468.toInt(), 160)
            c.drawCircle(topX - r * 0.3f, topY - r * 0.6f, r * 0.55f, p)
            for (e in ends) c.drawCircle(e[0] - r * 0.25f, e[1] - r * 0.3f, r * 0.45f, p)
        }
    }

    private fun drawDrop(c: Canvas, x: Float, y: Float, r: Float) {
        val path = Path()
        path.moveTo(x, y - r * 2.6f)
        path.lineTo(x - r * 0.92f, y - r * 0.35f)
        path.lineTo(x + r * 0.92f, y - r * 0.35f)
        path.close()
        fill(0xFF5BB4E8.toInt())
        c.drawPath(path, p)
        c.drawCircle(x, y, r, p)
        stroke(0xFFFFFFFF.toInt(), r * 0.18f, 200)
        rect.set(x - r * 0.6f, y - r * 0.6f, x + r * 0.6f, y + r * 0.6f)
        c.drawArc(rect, 110f, 60f, false, p)
    }

    private fun drawHud(c: Canvas, w: Float, h: Float, now: Long) {
        val ink = 0xFF2B1D14.toInt()

        text(c, "قولها مع كل نقطة", w * 0.5f, h * 0.085f, w * 0.045f, 0xFF3E2D20.toInt())
        text(c, "أستغفر الله", w * 0.5f, h * 0.165f, w * 0.17f, ink)

        // big count
        val num = arabic(count)
        val numX = w * 0.54f
        val numSize = w * 0.24f
        text(c, num, numX, h * 0.232f, numSize, ink)
        val nw = tp.measureText(num)
        val maxS = arabic(MAX)
        text(c, maxS, numX - nw / 2f - w * 0.03f, h * 0.232f, w * 0.06f, 0xFF3E2D20.toInt(), Paint.Align.RIGHT)
        val mw = tp.measureText(maxS)
        text(c, "/", numX - nw / 2f - w * 0.03f - mw - w * 0.02f, h * 0.232f, w * 0.06f, 0xFF3E2D20.toInt(), Paint.Align.RIGHT)

        // progress bar (fills from right to left)
        val left = 0.10f * w
        val right = 0.90f * w
        val by = h * 0.263f
        stroke(0xFFF5EFE0.toInt(), w * 0.018f)
        c.drawLine(left, by, right, by, p)
        val fx = right - (shown / MAX) * (right - left)
        if (fx < right - 1f) {
            stroke(0xFF5B9BD5.toInt(), w * 0.018f)
            c.drawLine(fx, by, right, by, p)
        }
        val names = arrayOf("مطر", "جنات", "أنهار", "مغفرة")
        for (k in 0..3) {
            val mx = right - (k + 1) * 0.25f * (right - left)
            val reached = count >= (k + 1) * 25
            fill(if (reached) 0xFFE0A82E.toInt() else 0xFFFFFFFF.toInt())
            c.drawCircle(mx, by, w * 0.021f, p)
            stroke(if (reached) 0xFFB9851A.toInt() else 0xFFBBBBBB.toInt(), w * 0.003f)
            c.drawCircle(mx, by, w * 0.021f, p)
            text(c, names[k], mx, h * 0.288f, w * 0.036f,
                if (reached) 0xFF8A6A1F.toInt() else 0xFF6B5444.toInt())
        }

        // verse card
        val v = cardV
        if (v != null) {
            val age = (now - cardStart).toFloat()
            val done = count >= MAX
            var a = cl(age / 300f)
            if (!done) a = min(a, cl((4500f - age) / 500f))
            if (!done && age > 4500f) cardV = null
            if (a > 0f) {
                val al = (255 * a).toInt()
                rect.set(w * 0.14f, h * 0.315f, w * 0.86f, h * 0.405f)
                fill(0xFFFFF8E8.toInt(), al)
                c.drawRoundRect(rect, w * 0.04f, w * 0.04f, p)
                stroke(0xFFD4A937.toInt(), w * 0.004f, al)
                c.drawRoundRect(rect, w * 0.04f, w * 0.04f, p)
                val vs = fit(v[0], w * 0.64f, w * 0.075f)
                text(c, v[0], w * 0.5f, h * 0.358f, vs, 0xFF8A6A1F.toInt(), alpha = al)
                val rs = fit(v[1], w * 0.64f, w * 0.038f)
                text(c, v[1], w * 0.5f, h * 0.392f, rs, 0xFF3E2D20.toInt(), alpha = al)
            }
        }

        // developer credit
        tp.setShadowLayer(w * 0.01f, 0f, 0f, Color.BLACK)
        text(c, DEVELOPER, w * 0.5f, h * 0.975f, w * 0.036f, Color.WHITE, alpha = 220)
        tp.clearShadowLayer()
    }
}
