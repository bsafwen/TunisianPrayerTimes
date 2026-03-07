package com.tunisianprayertimes

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewFlipper: ViewFlipper
    private val handler = Handler(Looper.getMainLooper())
    private val progressBars = mutableListOf<View>()
    private var currentStep = 0
    private val totalSteps = 4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        viewFlipper = findViewById(R.id.viewFlipper)

        progressBars.add(findViewById(R.id.progressBar1))
        progressBars.add(findViewById(R.id.progressBar2))
        progressBars.add(findViewById(R.id.progressBar3))
        progressBars.add(findViewById(R.id.progressBar4))

        // Skip button
        findViewById<View>(R.id.tvSkip).setOnClickListener { finish() }

        // Start button on last step
        findViewById<View>(R.id.btnStart).setOnClickListener { finish() }

        // Next button
        findViewById<View>(R.id.btnNext).setOnClickListener { advanceStep() }

        // Start the sequence
        startStep(0)
    }

    private fun startStep(step: Int) {
        currentStep = step

        // Fill progress for completed steps
        for (i in 0 until step) {
            fillProgress(i, instant = true)
        }
        // Reset future steps
        for (i in step until totalSteps) {
            resetProgress(i)
        }

        // Fill current step's progress bar instantly
        fillProgress(step, instant = true)

        // Run step-specific animations
        when (step) {
            2 -> handler.postDelayed({ animateStep3() }, 800)
        }

        // Show Next on steps 0-2, hide on last step; show Start only on last step
        val isLastStep = step == totalSteps - 1
        findViewById<View>(R.id.btnNext).visibility = if (isLastStep) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnStart).visibility = if (isLastStep) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tvSkip).visibility = if (isLastStep) View.GONE else View.VISIBLE
    }

    private fun advanceStep() {
        handler.removeCallbacksAndMessages(null)
        if (currentStep < totalSteps - 1) {
            fillProgress(currentStep, instant = true)
            viewFlipper.showNext()
            startStep(currentStep + 1)
        }
    }

    private fun fillProgress(step: Int, instant: Boolean) {
        val bar = progressBars[step]
        bar.setBackgroundColor(ContextCompat.getColor(this, R.color.gold))
        bar.scaleX = 1f
    }

    private fun resetProgress(step: Int) {
        val bar = progressBars[step]
        bar.setBackgroundColor(ContextCompat.getColor(this, R.color.gold_light))
        bar.scaleX = 1f
    }

    /**
     * Animate step 3: pulse the "د" label, then swap row from duration → fixed time mode.
     */
    private fun animateStep3() {
        val pulseLabel = findViewById<View>(R.id.pulseLabel) ?: return
        val rowBefore = findViewById<View>(R.id.rowBefore) ?: return
        val rowAfter = findViewById<View>(R.id.rowAfter) ?: return
        val tapIndicator = findViewById<View>(R.id.tapIndicator) ?: return

        // Pulse animation on the "د" label
        val pulseScaleX = ObjectAnimator.ofFloat(pulseLabel, "scaleX", 1f, 1.4f, 1f)
        val pulseScaleY = ObjectAnimator.ofFloat(pulseLabel, "scaleY", 1f, 1.4f, 1f)
        val pulseSet = AnimatorSet().apply {
            playTogether(pulseScaleX, pulseScaleY)
            duration = 600
            interpolator = AccelerateDecelerateInterpolator()
        }

        // Bounce the tap indicator
        val bounce = ObjectAnimator.ofFloat(tapIndicator, "translationY", 0f, -20f, 0f).apply {
            duration = 500
            repeatCount = 2
            interpolator = AccelerateDecelerateInterpolator()
        }
        bounce.start()

        // After 1.5 sec: pulse "د", then swap to fixed time
        handler.postDelayed({
            pulseSet.start()

            handler.postDelayed({
                // Fade out duration row, fade in fixed time row
                rowBefore.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction {
                        rowBefore.visibility = View.GONE
                        rowAfter.visibility = View.VISIBLE
                        rowAfter.alpha = 0f
                        rowAfter.animate()
                            .alpha(1f)
                            .setDuration(300)
                            .start()
                    }
                    .start()

                // Hide tap indicator
                tapIndicator.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .start()

                // Update description
                val desc = findViewById<android.widget.TextView>(R.id.step3Desc)
                desc?.text = getString(R.string.onboarding_step3_after)
            }, 700)
        }, 1000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
