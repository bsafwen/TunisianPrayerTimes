package com.tunisianprayertimes

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.tunisianprayertimes.databinding.ActivityMainBinding
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private var allDelegations: List<Delegation> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        setupAutoSilenceToggle()
        setupLocationPicker()
        setupPrayerRows()
        updateRamadanIndicator()

        // On first launch, show onboarding tutorial and request DND permission
        if (PrefsManager.isFirstLaunch(this)) {
            PrefsManager.markFirstLaunchDone(this)
            startActivity(Intent(this, OnboardingActivity::class.java))
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                requestDndPermission()
            }
        }

        // Sync ringer state with prayer schedule on every app open
        if (PrefsManager.isEnabled(this)) {
            if (hasExactAlarmPermission()) {
                SilenceScheduler.scheduleAll(this)
            } else {
                ensureExactAlarmPermission()
            }
        }
        updateUI()

        binding.btnToggleSilence.setOnClickListener {
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                requestDndPermission()
                return@setOnClickListener
            }
            val isSilent = audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT
            if (isSilent) {
                disableSilentMode()
            } else {
                enableSilentMode()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // After returning from DND or alarm settings, activate scheduling if permissions granted
        if (PrefsManager.isEnabled(this) && notificationManager.isNotificationPolicyAccessGranted && hasExactAlarmPermission()) {
            SilenceScheduler.scheduleAll(this)
        }
        updateUI()
    }

    private fun setupAutoSilenceToggle() {
        binding.switchAutoSilence.isChecked = PrefsManager.isEnabled(this)
        binding.switchAutoSilence.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setEnabled(this, isChecked)
            if (isChecked) {
                if (!notificationManager.isNotificationPolicyAccessGranted) {
                    requestDndPermission()
                    binding.switchAutoSilence.isChecked = false
                    PrefsManager.setEnabled(this, false)
                } else if (!hasExactAlarmPermission()) {
                    ensureExactAlarmPermission()
                    Toast.makeText(this, getString(R.string.toast_auto_enabled), Toast.LENGTH_SHORT).show()
                } else {
                    SilenceScheduler.scheduleAll(this)
                    Toast.makeText(this, getString(R.string.toast_auto_enabled), Toast.LENGTH_SHORT).show()
                }
            } else {
                SilenceScheduler.cancelAll(this)
                Toast.makeText(this, getString(R.string.toast_auto_disabled), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateRamadanIndicator() {
        binding.tvRamadanIndicator.visibility = if (RamadanDetector.isRamadan()) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }

    private fun setupLocationPicker() {
        allDelegations = GouvernoratRepository.loadAllDelegations(this)
        val adapter = DelegationSearchAdapter(this, allDelegations)
        binding.actvDelegation.setAdapter(adapter)

        // Restore saved delegation
        val savedId = PrefsManager.getDelegationId(this)
        val saved = GouvernoratRepository.findDelegationById(this, savedId)
        if (saved != null) {
            binding.actvDelegation.setText(saved.displayName(), false)
        }

        binding.actvDelegation.setOnItemClickListener { parent, _, position, _ ->
            val delegation = parent.getItemAtPosition(position) as Delegation
            PrefsManager.setDelegationId(this, delegation.id)
            setupPrayerRows()
            rescheduleIfEnabled()
        }

        // Show all results when field is tapped
        binding.actvDelegation.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.actvDelegation.showDropDown()
        }
        binding.actvDelegation.setOnClickListener {
            binding.actvDelegation.showDropDown()
        }
    }

    private fun setupPrayerRows() {
        val container = binding.prayerRowsContainer
        container.removeAllViews()

        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1
        val day = now.get(Calendar.DAY_OF_MONTH)
        val delegationId = PrefsManager.getDelegationId(this)

        val todayTimes = try {
            PrayerTimesRepository.loadDayPrayerTimes(this, delegationId, year, month, day)
        } catch (e: Exception) {
            null
        }

        val prayerNames = mapOf(
            Prayer.FAJR to getString(R.string.prayer_fajr),
            Prayer.DHUHR to getString(R.string.prayer_dhuhr),
            Prayer.ASR to getString(R.string.prayer_asr),
            Prayer.MAGHRIB to getString(R.string.prayer_maghrib),
            Prayer.ISHA to getString(R.string.prayer_isha)
        )

        for (prayer in Prayer.values()) {
            val rowView = LayoutInflater.from(this).inflate(R.layout.item_prayer_row, container, false)

            val tvName = rowView.findViewById<TextView>(R.id.tvPrayerName)
            val tvTime = rowView.findViewById<TextView>(R.id.tvPrayerTime)
            val tvLabel = rowView.findViewById<TextView>(R.id.tvAfterLabel)
            val etAfter = rowView.findViewById<EditText>(R.id.etAfter)
            val tvFixedTime = rowView.findViewById<TextView>(R.id.tvFixedTime)

            tvName.text = prayerNames[prayer] ?: prayer.name

            // Show today's prayer time
            val prayerTime = todayTimes?.allPrayers()?.find { it.prayer == prayer }
            tvTime.text = if (prayerTime != null) {
                String.format("%02d:%02d", prayerTime.hour, prayerTime.minute)
            } else {
                "--:--"
            }

            // Load saved duration
            etAfter.setText(PrefsManager.getAfterMinutes(this, prayer).toString())

            // Load or compute default fixed time
            var fixedH = PrefsManager.getFixedTimeHour(this, prayer)
            var fixedM = PrefsManager.getFixedTimeMinute(this, prayer)
            if (fixedH < 0 || fixedM < 0) {
                // Default: prayer time + current duration
                if (prayerTime != null) {
                    val defaultEnd = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, prayerTime.hour)
                        set(Calendar.MINUTE, prayerTime.minute)
                        add(Calendar.MINUTE, PrefsManager.getAfterMinutes(this@MainActivity, prayer))
                    }
                    fixedH = defaultEnd.get(Calendar.HOUR_OF_DAY)
                    fixedM = defaultEnd.get(Calendar.MINUTE)
                    PrefsManager.setFixedTime(this, prayer, fixedH, fixedM)
                } else {
                    fixedH = 12; fixedM = 0
                }
            }
            tvFixedTime.text = String.format("%02d:%02d", fixedH, fixedM)

            // Set initial mode
            val mode = PrefsManager.getSilenceMode(this, prayer)
            applyRowMode(mode, tvLabel, etAfter, tvFixedTime)

            // Tap label to toggle mode
            tvLabel.setOnClickListener {
                val current = PrefsManager.getSilenceMode(this, prayer)
                val newMode = if (current == SilenceMode.DURATION) SilenceMode.FIXED_TIME else SilenceMode.DURATION
                PrefsManager.setSilenceMode(this, prayer, newMode)
                applyRowMode(newMode, tvLabel, etAfter, tvFixedTime)
                rescheduleIfEnabled()
            }

            // Tap fixed time to open time picker
            tvFixedTime.setOnClickListener {
                val h = PrefsManager.getFixedTimeHour(this, prayer)
                val m = PrefsManager.getFixedTimeMinute(this, prayer)
                val picker = MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_24H)
                    .setHour(if (h >= 0) h else 12)
                    .setMinute(if (m >= 0) m else 0)
                    .setTitleText(getString(R.string.pick_end_time))
                    .build()
                picker.addOnPositiveButtonClickListener {
                    tvFixedTime.text = String.format("%02d:%02d", picker.hour, picker.minute)
                    PrefsManager.setFixedTime(this, prayer, picker.hour, picker.minute)
                    rescheduleIfEnabled()
                }
                picker.show(supportFragmentManager, "picker_${prayer.name}")
            }

            // Save duration on change
            etAfter.addTextChangedListener(createSaveWatcher { text ->
                val minutes = text.toIntOrNull() ?: 0
                PrefsManager.setAfterMinutes(this, prayer, minutes)
                rescheduleIfEnabled()
            })

            container.addView(rowView)
        }
    }

    private fun applyRowMode(mode: SilenceMode, label: TextView, etDuration: EditText, tvFixed: TextView) {
        when (mode) {
            SilenceMode.DURATION -> {
                label.text = getString(R.string.label_duration)
                etDuration.visibility = View.VISIBLE
                tvFixed.visibility = View.GONE
            }
            SilenceMode.FIXED_TIME -> {
                label.text = getString(R.string.label_fixed_time)
                etDuration.visibility = View.GONE
                tvFixed.visibility = View.VISIBLE
            }
        }
    }

    private fun createSaveWatcher(onSave: (String) -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onSave(s?.toString() ?: "0")
            }
        }
    }

    private fun rescheduleIfEnabled() {
        if (PrefsManager.isEnabled(this)) {
            SilenceScheduler.scheduleAll(this)
        }
    }

    private fun enableSilentMode() {
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        updateUI()
        Toast.makeText(this, getString(R.string.toast_silent_enabled), Toast.LENGTH_SHORT).show()
    }

    private fun disableSilentMode() {
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        updateUI()
        Toast.makeText(this, getString(R.string.toast_normal_restored), Toast.LENGTH_SHORT).show()
    }

    private fun requestDndPermission() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dnd_dialog_title))
            .setMessage(getString(R.string.dnd_dialog_message))
            .setPositiveButton(getString(R.string.dnd_dialog_grant)) { _, _ ->
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.dnd_dialog_later), null)
            .setCancelable(false)
            .show()
    }

    private fun ensureExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }
        }
    }

    private fun hasExactAlarmPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return alarmManager.canScheduleExactAlarms()
        }
        return true
    }

    private fun updateUI() {
        val isSilent = audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT
        val hasDndAccess = notificationManager.isNotificationPolicyAccessGranted

        binding.tvStatus.text = when {
            !hasDndAccess -> getString(R.string.status_no_permission)
            isSilent -> getString(R.string.status_silent)
            else -> getString(R.string.status_normal)
        }

        if (isSilent && hasDndAccess) {
            binding.btnToggleSilence.text = getString(R.string.btn_unsilence)
            binding.btnToggleSilence.setIconResource(android.R.drawable.ic_lock_silent_mode_off)
            binding.btnToggleSilence.setBackgroundColor(getColor(R.color.silence_red))
        } else {
            binding.btnToggleSilence.text = getString(R.string.btn_silence)
            binding.btnToggleSilence.setIconResource(android.R.drawable.ic_lock_silent_mode)
            binding.btnToggleSilence.setBackgroundColor(getColor(R.color.green_primary))
        }
    }
}
