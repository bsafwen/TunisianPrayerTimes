package com.tunisianprayertimes

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewFlipper: ViewFlipper
    private lateinit var notificationManager: NotificationManager
    private val handler = Handler(Looper.getMainLooper())
    private val progressBars = mutableListOf<View>()
    private var currentStep = 0
    private val totalSteps = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        viewFlipper = findViewById(R.id.viewFlipper)

        progressBars.add(findViewById(R.id.progressBar1))
        progressBars.add(findViewById(R.id.progressBar2))
        progressBars.add(findViewById(R.id.progressBar3))
        progressBars.add(findViewById(R.id.progressBar4))
        progressBars.add(findViewById(R.id.progressBar5))

        findViewById<View>(R.id.btnStart).setOnClickListener { finish() }
        findViewById<View>(R.id.btnNext).setOnClickListener { advanceStep() }
        findViewById<View>(R.id.btnPrev).setOnClickListener { goBack() }

        setupPermissionButtons()
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
        findViewById<View>(R.id.navButtons).visibility = View.VISIBLE
        findViewById<View>(R.id.btnPrev).visibility = if (step == 0) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnNext).visibility = if (isLastStep) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnStart).visibility = if (isLastStep) View.VISIBLE else View.GONE

        if (step == 2) {
            handler.postDelayed({ animateDemoTap() }, 600)
        }
        if (step == 3) {
            updatePermissionButtons()
        }
    }

    private fun setupPermissionButtons() {
        findViewById<MaterialButton>(R.id.btnGrantDnd).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.btnGrantAlarm).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }
        }
    }

    private fun updatePermissionButtons() {
        val btnDnd = findViewById<MaterialButton>(R.id.btnGrantDnd)
        val btnAlarm = findViewById<MaterialButton>(R.id.btnGrantAlarm)

        val hasDnd = notificationManager.isNotificationPolicyAccessGranted
        if (hasDnd) {
            btnDnd.text = getString(R.string.onboarding_perm_granted)
            btnDnd.isEnabled = false
            btnDnd.setBackgroundColor(ContextCompat.getColor(this, R.color.text_muted))
        } else {
            btnDnd.text = getString(R.string.onboarding_perm_grant)
            btnDnd.isEnabled = true
            btnDnd.setBackgroundColor(ContextCompat.getColor(this, R.color.green_primary))
        }

        val hasAlarm = hasExactAlarmPermission()
        if (hasAlarm) {
            btnAlarm.text = getString(R.string.onboarding_perm_granted)
            btnAlarm.isEnabled = false
            btnAlarm.setBackgroundColor(ContextCompat.getColor(this, R.color.text_muted))
        } else {
            btnAlarm.text = getString(R.string.onboarding_perm_grant)
            btnAlarm.isEnabled = true
            btnAlarm.setBackgroundColor(ContextCompat.getColor(this, R.color.green_primary))
        }
    }

    private fun hasExactAlarmPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return alarmManager.canScheduleExactAlarms()
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        if (currentStep == 3) {
            updatePermissionButtons()
        }
    }

    private fun advanceStep() {
        handler.removeCallbacksAndMessages(null)
        if (currentStep < totalSteps - 1) {
            viewFlipper.showNext()
            startStep(currentStep + 1)
        }
    }

    private fun goBack() {
        handler.removeCallbacksAndMessages(null)
        if (currentStep > 0) {
            // Reverse slide direction for going back
            viewFlipper.setInAnimation(this, R.anim.slide_in_right)
            viewFlipper.setOutAnimation(this, R.anim.slide_out_left)
            viewFlipper.showPrevious()
            // Restore forward animations
            viewFlipper.setInAnimation(this, R.anim.slide_in_left)
            viewFlipper.setOutAnimation(this, R.anim.slide_out_right)
            resetStep3Demo()
            startStep(currentStep - 1)
        }
    }

    private fun resetStep3Demo() {
        val demoLabel = findViewById<TextView>(R.id.demoLabel) ?: return
        val ripple = findViewById<View>(R.id.tapRipple) ?: return
        val rowBefore = findViewById<View>(R.id.rowBefore) ?: return
        val rowAfter = findViewById<View>(R.id.rowAfter) ?: return
        val step3Desc = findViewById<TextView>(R.id.step3Desc) ?: return

        demoLabel.text = getString(R.string.label_duration)
        demoLabel.setTextColor(ContextCompat.getColor(this, R.color.gold))
        demoLabel.scaleX = 1f
        demoLabel.scaleY = 1f
        ripple.alpha = 0f
        ripple.scaleX = 1f
        ripple.scaleY = 1f
        ripple.visibility = View.INVISIBLE
        rowBefore.alpha = 1f
        rowAfter.alpha = 0f
        rowAfter.visibility = View.INVISIBLE
        step3Desc.text = getString(R.string.onboarding_step3_desc)
    }

    private fun animateDemoTap() {
        val demoLabel = findViewById<TextView>(R.id.demoLabel) ?: return
        val ripple = findViewById<View>(R.id.tapRipple) ?: return
        val rowBefore = findViewById<View>(R.id.rowBefore) ?: return
        val rowAfter = findViewById<View>(R.id.rowAfter) ?: return
        val step3Desc = findViewById<TextView>(R.id.step3Desc) ?: return

        // Create circular ripple drawable
        val goldColor = ContextCompat.getColor(this, R.color.gold)
        val rippleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(goldColor)
        }
        ripple.background = rippleBg

        // 1. After a short delay, show the ripple expanding from the label
        handler.postDelayed({
            // Reset ripple to small size centered on label
            ripple.scaleX = 0.3f
            ripple.scaleY = 0.3f
            ripple.alpha = 0.7f
            ripple.visibility = View.VISIBLE

            // Expand the ripple circle outward while fading
            ripple.animate()
                .scaleX(3f).scaleY(3f)
                .alpha(0f)
                .setDuration(600)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    ripple.visibility = View.INVISIBLE

                    // Change the label text from "د" to "حتى"
                    demoLabel.setTextColor(ContextCompat.getColor(this, R.color.green_primary))
                    demoLabel.text = getString(R.string.label_fixed_time)

                    // Brief scale pop on the new label
                    demoLabel.scaleX = 0.8f
                    demoLabel.scaleY = 0.8f
                    demoLabel.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(200)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()

                    // Crossfade from duration row to fixed time row
                    handler.postDelayed({
                        rowBefore.animate().alpha(0f).setDuration(300).start()
                        rowAfter.visibility = View.VISIBLE
                        rowAfter.animate().alpha(1f).setDuration(300).start()
                        step3Desc.text = getString(R.string.onboarding_step3_after)
                    }, 500)
                }
                .start()
        }, 800)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
