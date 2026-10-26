package com.fongmi.android.tv.ui.custom

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import androidx.appcompat.content.res.AppCompatResources
import com.fongmi.android.tv.R
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A brief, non-interactive playback badge. Its measured bounds stay still during motion: only the
 * drawn pill and its clip expand, so playback surfaces never have to relayout on animation frames.
 * A small, already-blurred video crop may be supplied by the host; this view never captures video.
 */
class JetStreamPlaybackSpecsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val textDensity = min(resources.displayMetrics.scaledDensity, density * 1.3f)
    private val pillHeight = 46f * density
    private val endPadding = 18f * density
    private val contentStart = 52f * density
    private val columnGap = 25f * density
    private val ratingGap = 13f * density
    private val ease = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val logoSpring = OvershootInterpolator(1.15f)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD51B1B1D.toInt() }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x26FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 0.75f * density
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x36FFFFFF
        strokeWidth = 0.75f * density
    }
    private val ratingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.75f * density
    }
    private val primaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f * textDensity
        color = Color.WHITE
        typeface = Typeface.create(jetStreamTypeface(context), Typeface.BOLD)
    }
    private val secondaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * textDensity
        color = 0xFFB8B8BD.toInt()
        typeface = jetStreamTypeface(context)
    }
    private val ratingTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * textDensity
        color = 0xFFE2E2E7.toInt()
        typeface = Typeface.create(jetStreamTypeface(context), Typeface.BOLD)
    }
    private val logo = AppCompatResources.getDrawable(context, R.drawable.ic_logo)?.mutate()
    private val pill = RectF()
    private val fullBounds = RectF()
    private val ratingBounds = RectF()
    private val clip = Path()
    private val logoClip = Path()
    private var backdrop: Bitmap? = null
    private var videoPrimary = ""
    private var videoSecondary = ""
    private var audioPrimary = ""
    private var audioSecondary = ""
    private var rating = ""
    private var displayVideoPrimary = ""
    private var displayVideoSecondary = ""
    private var displayAudioPrimary = ""
    private var displayAudioSecondary = ""
    private var displayRating = ""
    private var naturalVideoWidth = 0f
    private var naturalAudioWidth = 0f
    private var naturalRatingWidth = 0f
    private var audioStart = 0f
    private var ratingStart = 0f
    private var visibleAudio = false
    private var visibleRating = false
    private var expansion = 0f
    private var textOpacity = 0f
    private var contentOffset = 0f
    private var logoScale = 1f
    private var logoRotation = 0f
    private var active = false
    private var animator: ValueAnimator? = null
    private val enter = Runnable { enter() }
    private val holdFinished = Runnable { collapse() }

    init {
        visibility = GONE
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        // This information is always anchored at the left, including RTL playback screens.
        layoutDirection = LAYOUT_DIRECTION_LTR
        setWillNotDraw(false)
    }

    /** Starts a new playback presentation, replacing any in-flight animation and old video crop. */
    fun show(videoPrimary: String?, videoSecondary: String?, audioPrimary: String?,
             audioSecondary: String?, rating: String?) {
        dismiss()
        setInfo(videoPrimary, videoSecondary, audioPrimary, audioSecondary, rating)
        if (!hasInfo()) return
        active = true
        expansion = 0f
        textOpacity = 0f
        logoScale = 0.7f
        logoRotation = -14f
        alpha = 0f
        visibility = VISIBLE
        requestLayout()
        post(enter)
    }

    /** Refreshes late-arriving track metadata without replaying the entrance or extending the hold. */
    fun update(videoPrimary: String?, videoSecondary: String?, audioPrimary: String?,
               audioSecondary: String?, rating: String?) {
        if (!active) return
        setInfo(videoPrimary, videoSecondary, audioPrimary, audioSecondary, rating)
        if (!hasInfo()) dismiss() else {
            requestLayout()
            invalidate()
        }
    }

    /** The bitmap is borrowed. Its producer clears it before recycling; late inactive results are ignored. */
    fun setBackdrop(bitmap: Bitmap?) {
        backdrop = bitmap?.takeIf { active && !it.isRecycled }
        bodyPaint.color = if (backdrop == null) 0xD51B1B1D.toInt() else 0xB817171A.toInt()
        if (active) invalidate()
    }

    /** Full local crop to capture, independent of the currently animated width. */
    fun getExpandedBounds(out: Rect) {
        out.set(0, 0, measuredWidth, measuredHeight)
    }

    fun isActive(): Boolean = active

    /** Immediate cancellation for stop, stream switches, hidden controls, and host destruction. */
    fun dismiss() {
        active = false
        removeCallbacks(enter)
        removeCallbacks(holdFinished)
        animator?.removeAllListeners()
        animator?.removeAllUpdateListeners()
        animator?.cancel()
        animator = null
        backdrop = null
        bodyPaint.color = 0xD51B1B1D.toInt()
        expansion = 0f
        textOpacity = 0f
        alpha = 0f
        scaleX = 1f
        scaleY = 1f
        visibility = GONE
    }

    private fun setInfo(videoPrimary: String?, videoSecondary: String?, audioPrimary: String?,
                        audioSecondary: String?, rating: String?) {
        this.videoPrimary = clean(videoPrimary)
        this.videoSecondary = clean(videoSecondary)
        this.audioPrimary = clean(audioPrimary)
        this.audioSecondary = clean(audioSecondary)
        this.rating = clean(rating).take(20)
        naturalVideoWidth = max(primaryPaint.measureText(this.videoPrimary),
            secondaryPaint.measureText(this.videoSecondary)).coerceAtMost(205f * density)
        naturalAudioWidth = max(primaryPaint.measureText(this.audioPrimary),
            secondaryPaint.measureText(this.audioSecondary)).coerceAtMost(100f * density)
        naturalRatingWidth = if (this.rating.isEmpty()) 0f else
            ratingTextPaint.measureText(this.rating).coerceAtMost(65f * density) + 12f * density
        contentDescription = listOf(this.videoPrimary, this.videoSecondary, this.audioPrimary,
            this.audioSecondary, this.rating).filter { it.isNotEmpty() }.joinToString(", ")
    }

    private fun clean(value: String?): String = value.orEmpty().replace('\n', ' ')
        .replace('\r', ' ').trim().take(96)

    private fun hasInfo(): Boolean = videoPrimary.isNotEmpty() || videoSecondary.isNotEmpty() ||
        audioPrimary.isNotEmpty() || audioSecondary.isNotEmpty()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val videoWidth = naturalVideoWidth
        val audioWidth = if (naturalAudioWidth > 0f) naturalAudioWidth +
            (if (videoWidth > 0f) columnGap else 0f) else 0f
        val ratingWidth = if (naturalRatingWidth > 0f) naturalRatingWidth + ratingGap else 0f
        val desiredWidth = max(pillHeight, contentStart + videoWidth + audioWidth + ratingWidth + endPadding)
        // Text measurements are fractional: rounding down can hide a fitting rating or ellipsize a line.
        setMeasuredDimension(resolveSize(ceil(desiredWidth).toInt(), widthMeasureSpec),
            resolveSize(ceil(pillHeight).toInt(), heightMeasureSpec))
        layoutText(measuredWidth.toFloat())
    }

    private fun layoutText(availableWidth: Float) {
        val available = (availableWidth - contentStart - endPadding).coerceAtLeast(0f)
        visibleAudio = naturalAudioWidth > 0f
        // If a preview window is exceptionally narrow, the video/audio facts have priority.
        visibleRating = naturalRatingWidth > 0f && available >= naturalVideoWidth +
            naturalAudioWidth + (if (naturalVideoWidth > 0f && visibleAudio) columnGap else 0f) +
            ratingGap + naturalRatingWidth
        val usedRating = if (visibleRating) naturalRatingWidth + ratingGap else 0f
        val gap = if (naturalVideoWidth > 0f && visibleAudio) columnGap else 0f
        val columnsWidth = (available - usedRating - gap).coerceAtLeast(0f)
        var videoWidth = naturalVideoWidth
        var audioWidth = naturalAudioWidth
        if (videoWidth + audioWidth > columnsWidth) {
            audioWidth = min(audioWidth, columnsWidth * (if (videoWidth > 0f) 0.32f else 1f))
            videoWidth = min(videoWidth, (columnsWidth - audioWidth).coerceAtLeast(0f))
        }
        displayVideoPrimary = ellipsize(videoPrimary, primaryPaint, videoWidth)
        displayVideoSecondary = ellipsize(videoSecondary, secondaryPaint, videoWidth)
        displayAudioPrimary = ellipsize(audioPrimary, primaryPaint, audioWidth)
        displayAudioSecondary = ellipsize(audioSecondary, secondaryPaint, audioWidth)
        displayRating = ellipsize(rating, ratingTextPaint, naturalRatingWidth - 12f * density)
        visibleAudio = visibleAudio && audioWidth > 0f
        audioStart = contentStart + videoWidth + gap
        ratingStart = contentStart + videoWidth + gap + audioWidth + ratingGap
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Float): String =
        if (width <= 0f) "" else TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fullBounds.set(0f, 0f, w.toFloat(), h.toFloat())
        glassPaint.shader = LinearGradient(0f, 0f, 0f, h.coerceAtLeast(1).toFloat(),
            0x16FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        pivotX = h / 2f
        pivotY = h / 2f
        val size = min(h * 0.68f, 32f * density).toInt()
        logo?.setBounds(0, 0, size, size)
        logoClip.reset()
        logoClip.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active || width == 0 || height == 0) return
        val radius = height / 2f
        val currentWidth = min(width.toFloat(), height + (width - height) * expansion)
        pill.set(0f, 0f, currentWidth, height.toFloat())
        clip.reset()
        clip.addRoundRect(pill, radius, radius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        backdrop?.takeUnless { it.isRecycled }?.let { canvas.drawBitmap(it, null, fullBounds, bitmapPaint) }
        canvas.drawRoundRect(pill, radius, radius, bodyPaint)
        canvas.drawRoundRect(pill, radius, radius, glassPaint)
        if (textOpacity > 0f) drawInfo(canvas)
        drawLogo(canvas, radius)
        canvas.restoreToCount(save)
        val inset = rimPaint.strokeWidth / 2f
        pill.inset(inset, inset)
        canvas.drawRoundRect(pill, radius, radius, rimPaint)
    }

    private fun drawLogo(canvas: Canvas, center: Float) {
        val drawable = logo ?: return
        val size = drawable.bounds.width().toFloat()
        val save = canvas.save()
        canvas.translate(center, center)
        canvas.rotate(logoRotation)
        canvas.scale(logoScale, logoScale)
        canvas.translate(-size / 2f, -size / 2f)
        canvas.clipPath(logoClip)
        drawable.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawInfo(canvas: Canvas) {
        val save = canvas.save()
        // The content has a separate clip so incoming letters never cross the fixed logo disc.
        canvas.clipRect(height.toFloat(), 0f, width.toFloat(), height.toFloat())
        canvas.translate(contentOffset, 0f)
        primaryPaint.alpha = (255 * textOpacity).toInt()
        secondaryPaint.alpha = (255 * textOpacity).toInt()
        val center = height / 2f
        val primaryBaseline = center - 2f * density
        val secondaryBaseline = center + 12f * density
        drawLines(canvas, displayVideoPrimary, displayVideoSecondary, contentStart,
            center, primaryBaseline, secondaryBaseline)
        if (visibleAudio) {
            if (naturalVideoWidth > 0f) {
                dividerPaint.alpha = (54 * textOpacity).toInt()
                val x = audioStart - columnGap / 2f
                canvas.drawLine(x, center - 13f * density, x, center + 13f * density, dividerPaint)
            }
            drawLines(canvas, displayAudioPrimary, displayAudioSecondary, audioStart,
                center, primaryBaseline, secondaryBaseline)
        }
        if (visibleRating) {
            ratingPaint.color = Color.argb((68 * textOpacity).toInt(), 255, 255, 255)
            ratingTextPaint.alpha = (225 * textOpacity).toInt()
            ratingBounds.set(ratingStart, center - 10f * density,
                ratingStart + naturalRatingWidth, center + 10f * density)
            canvas.drawRoundRect(ratingBounds, 4f * density, 4f * density, ratingPaint)
            val baseline = center - (ratingTextPaint.ascent() + ratingTextPaint.descent()) / 2f
            canvas.drawText(displayRating, ratingStart + 6f * density, baseline, ratingTextPaint)
        }
        canvas.restoreToCount(save)
    }

    private fun drawLines(canvas: Canvas, primary: String, secondary: String, x: Float,
                          center: Float, primaryBaseline: Float, secondaryBaseline: Float) {
        if (primary.isNotEmpty() && secondary.isNotEmpty()) {
            canvas.drawText(primary, x, primaryBaseline, primaryPaint)
            canvas.drawText(secondary, x, secondaryBaseline, secondaryPaint)
        } else if (primary.isNotEmpty()) {
            canvas.drawText(primary, x, center - (primaryPaint.ascent() + primaryPaint.descent()) / 2f, primaryPaint)
        } else if (secondary.isNotEmpty()) {
            canvas.drawText(secondary, x, center - (secondaryPaint.ascent() + secondaryPaint.descent()) / 2f, secondaryPaint)
        }
    }

    private fun enter() {
        if (!active) return
        if (!isAttachedToWindow || !isShown) {
            dismiss()
            return
        }
        if (!motionEnabled()) {
            alpha = 1f
            expansion = 1f
            textOpacity = 1f
            logoScale = 1f
            logoRotation = 0f
            contentOffset = 0f
            invalidate()
            postDelayed(holdFinished, HOLD_MILLIS)
            return
        }
        animatePhase(220, { progress ->
            alpha = ease.getInterpolation(progress)
            val spring = logoSpring.getInterpolation(progress)
            scaleX = 0.65f + 0.35f * spring
            scaleY = scaleX
            logoScale = 0.7f + 0.3f * spring
            logoRotation = -14f * (1f - ease.getInterpolation(progress))
        }) {
            scaleX = 1f
            scaleY = 1f
            animatePhase(520, { progress ->
                expansion = ease.getInterpolation(progress)
                val textProgress = ((progress - 0.12f) / 0.7f).coerceIn(0f, 1f)
                textOpacity = ease.getInterpolation(textProgress)
                contentOffset = -6f * density * (1f - textOpacity)
                // One restrained settle while the specifications arrive, never an idle spinner.
                logoScale = 1f + 0.065f * kotlin.math.sin(progress * Math.PI).toFloat()
            }) {
                logoScale = 1f
                postDelayed(holdFinished, HOLD_MILLIS)
            }
        }
    }

    private fun collapse() {
        if (!active) return
        if (!motionEnabled()) {
            dismiss()
            return
        }
        animatePhase(150, { progress -> textOpacity = 1f - progress }) {
            animatePhase(380, { progress -> expansion = 1f - ease.getInterpolation(progress) }) {
                animatePhase(180, { progress ->
                    alpha = 1f - progress
                    val scale = 1f - 0.3f * ease.getInterpolation(progress)
                    scaleX = scale
                    scaleY = scale
                    logoScale = scale
                }) { dismiss() }
            }
        }
    }

    private fun animatePhase(durationMillis: Long, frame: (Float) -> Unit, finished: () -> Unit) {
        if (!active) return
        val next = ValueAnimator.ofFloat(0f, 1f)
        animator = next
        next.duration = durationMillis
        next.interpolator = android.view.animation.LinearInterpolator()
        next.addUpdateListener {
            if (active) {
                frame(it.animatedValue as Float)
                invalidate()
            }
        }
        next.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (animator !== animation) return
                animator = null
                if (active) finished()
            }
        })
        next.start()
    }

    private fun motionEnabled(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ValueAnimator.areAnimatorsEnabled()
    } else {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }

    override fun onDetachedFromWindow() {
        dismiss()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE && active) dismiss()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE && active) dismiss()
    }

    companion object {
        private const val HOLD_MILLIS = 3_000L
    }
}
