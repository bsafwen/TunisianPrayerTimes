package com.tunisianprayertimes

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
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

        findViewById<View>(R.id.tvSkip).setOnClickListener { finish() }
        findViewById<View>(R.id.btnStart).setOnClickListener { finish() }
        findViewById<View>(R.id.btnNext).setOnClickListener { advanceStep() }

        startStep(0)
    }

    private fun startStep(step: Int) {
        currentStep = step

        for (i in 0 until step) {
            progressBars[i].setBackgroundColor(ContextCompat.getColor(this, R.color.gold))
        }
        for (i in step until totalSteps) {
            progressBars[i].setBackgroundColor(ContextCompat.getColor(this, R.color.gold_light))
        }
        progressBars[step].setBackgroundColor(ContextCompat.getColor(this, R.color.gold))

        val isLastStep = step == totalSteps - 1
        findViewById<View>(R.id.btnNext).visibility = if (isLastStep) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnStart).visibility = if (isLastStep) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tvSkip).visibility = if (isLastStep) View.GONE else View.VISIBLE

        if (step == 2) {
            handler.postDelayed({ animateDemoTap() }, 600)
        }
    }

    private fun advanceStep() {
        handler.removeCallbacksAndMessages(null)
        if (currentStep < totalSteps - 1) {
            viewFlipper.showNext()
            startStep(currentStep + 1)
        }
    }

    private fun animateDemoTap() {
        val finger = findViewById<View>(R.id.tapFinger) ?: return
        val demoLabel = findViewById<View>(R.id.demoLabel) ?: return
        val rowBefore = findViewById<View>(R.id.rowBefore) ?: return
        val rowAfter = findViewById<View>(R.id.rowAfter) ?: return
        val step3Desc = findViewById<TextView>(R.id.step3Desc) ?: return

        // 1. Bounce the finger up a few times
        val bounce = ObjectAnimator.ofFloat(finger, "translationY", 0f, -15f, 0f).apply {
            duration = 400
            repeatCount = 2
            interpolator = AccelerateDecelerateInterpolator()
        }
        bounce.start()

        // 2. After 1.5s: pulse the "د" label (scale up then down)
        handler.postDelayed({
            val pulseX = ObjectAnimator.ofFloat(demoLabel, "scaleX", 1f, 1.5f, 1f)
            val pulseY = ObjectAnimator.ofFloat(demoLabel, "scaleY", 1f, 1.5f, 1f)
            AnimatorSet().apply {
                playTogether(pulseX, pulseY)
                duration = 400
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }

            // 3. After pulse: crossfade from duration row to fixed time row
            handler.postDelayed({
                rowBefore.animate().alpha(0f).setDuration(300).start()
                rowAfter.visibility = View.VISIBLE
                rowAfter.animate().alpha(1f).setDuration(300).start()

                // Hide finger
                finger.animate().alpha(0f).setDuration(200).start()

                // Update description text
                step3Desc.text = getString(R.string.onboarding_step3_after)
            }, 500)
        }, 1500)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
