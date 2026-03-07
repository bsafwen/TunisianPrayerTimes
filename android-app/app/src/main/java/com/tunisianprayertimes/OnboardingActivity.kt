package com.tunisianprayertimes

import android.os.Bundle
import android.view.View
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewFlipper: ViewFlipper
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
    }

    private fun advanceStep() {
        if (currentStep < totalSteps - 1) {
            viewFlipper.showNext()
            startStep(currentStep + 1)
        }
    }
}
