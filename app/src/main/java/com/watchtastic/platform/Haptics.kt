package com.watchtastic.platform

import android.annotation.SuppressLint
import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The app's haptic vocabulary.
 *
 * On a watch the wrist is the primary output channel — the wearer often feels a result
 * before they look at it — so every meaningful outcome gets a distinct signature rather
 * than one generic buzz. The Pixel Watch's linear resonant actuator can render
 * [VibrationEffect.Composition] primitives with real amplitude control, so effects are
 * *composed* (rise, fall, double-tick) instead of being fixed-duration pulses. Devices
 * without primitive support fall back to predefined effects, then to plain waveforms.
 *
 * Deliberately quiet: navigation and scrolling are left alone, since Wear's rotary
 * scroller already provides its own crown detents and doubling up feels mushy.
 */
class Haptics(context: Context, private val prefs: Prefs) {

    private val vibrator: Vibrator? = runCatching {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    }.getOrNull()

    private val hasAmplitudeControl: Boolean =
        vibrator?.hasAmplitudeControl() == true

    // Spreading a vararg erases the @IntDef that lint wants to see on each element;
    // every call site below passes VibrationEffect.Composition constants.
    @SuppressLint("WrongConstant")
    private fun supports(vararg primitives: Int): Boolean =
        vibrator?.areAllPrimitivesSupported(*primitives) == true

    /** Touch feedback should duck under Do Not Disturb; alerts should not. */
    private val touchAttrs =
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)

    private val notificationAttrs =
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION)

    private fun play(effect: VibrationEffect, attrs: VibrationAttributes) {
        if (!prefs.haptics.value) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(effect, attrs) }
    }

    // ------------------------------------------------------------ vocabulary

    /** Faintest possible confirmation — value stepped, item focused. */
    fun tick() {
        val effect = if (supports(VibrationEffect.Composition.PRIMITIVE_TICK)) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f)
                .compose()
        } else {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        }
        play(effect, touchAttrs)
    }

    /** A press landed. */
    fun select() {
        val effect = if (supports(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f)
                .compose()
        } else {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        }
        play(effect, touchAttrs)
    }

    /** A message left the watch: a short upward gesture, like something departing. */
    fun sent() {
        val effect = if (
            supports(
                VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                VibrationEffect.Composition.PRIMITIVE_TICK,
            )
        ) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.4f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f, 40)
                .compose()
        } else {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        }
        play(effect, touchAttrs)
    }

    /** The mesh acknowledged it. Two light taps — "landed". */
    fun delivered() {
        val effect = if (supports(VibrationEffect.Composition.PRIMITIVE_TICK)) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f, 70)
                .compose()
        } else {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        }
        play(effect, notificationAttrs)
    }

    /** Something did not go through: a downward, heavier shape. */
    fun failed() {
        val effect = if (
            supports(
                VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
                VibrationEffect.Composition.PRIMITIVE_THUD,
            )
        ) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.7f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.6f, 60)
                .compose()
        } else {
            waveform(longArrayOf(0, 60, 80, 120), intArrayOf(0, 180, 0, 120))
        }
        play(effect, notificationAttrs)
    }

    /** A message arrived. Distinct from the system notification buzz. */
    fun incoming() {
        val effect = if (
            supports(
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_CLICK,
            )
        ) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f, 90)
                .compose()
        } else {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        }
        play(effect, notificationAttrs)
    }

    /**
     * Emergency/alert traffic. Long and unmistakable — this is the one pattern meant to
     * be noticed through a sleeve, so it ignores the subtlety budget.
     */
    fun alert() {
        val effect = if (
            supports(
                VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
                VibrationEffect.Composition.PRIMITIVE_CLICK,
            )
        ) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 1.0f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 100)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 150)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 150)
                .compose()
        } else {
            waveform(
                longArrayOf(0, 120, 100, 120, 100, 220),
                intArrayOf(0, 255, 0, 255, 0, 255),
            )
        }
        play(effect, notificationAttrs)
    }

    /** Radio link established. */
    fun connected() {
        val effect = if (supports(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE)) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.6f)
                .compose()
        } else {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        }
        play(effect, notificationAttrs)
    }

    /** Radio link lost. */
    fun disconnected() {
        val effect = if (supports(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL)) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.5f)
                .compose()
        } else {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        }
        play(effect, notificationAttrs)
    }

    /** Reached the end of a list or a value's range — a soft wall. */
    fun boundary() {
        val effect = if (supports(VibrationEffect.Composition.PRIMITIVE_THUD)) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.35f)
                .compose()
        } else {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        }
        play(effect, touchAttrs)
    }

    /** Long-press engaged a destructive or modal action. */
    fun heavy() {
        val effect = if (supports(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                .compose()
        } else {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        }
        play(effect, touchAttrs)
    }

    private fun waveform(timings: LongArray, amplitudes: IntArray): VibrationEffect =
        if (hasAmplitudeControl) {
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(timings, -1)
        }
}
