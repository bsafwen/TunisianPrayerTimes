package com.tunisianprayertimes

import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
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

        // On first launch, request DND permission and enable scheduling
        if (PrefsManager.isFirstLaunch(this)) {
            PrefsManager.markFirstLaunchDone(this)
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                requestDndPermission()
            }
        }

        // Sync ringer state with prayer schedule on every app open
        if (PrefsManager.isEnabled(this)) {
            SilenceScheduler.scheduleAll(this)
        }
        updateUI()

        binding.btnSilence.setOnClickListener {
            if (notificationManager.isNotificationPolicyAccessGranted) {
                enableSilentMode()
            } else {
                requestDndPermission()
            }
        }

        binding.btnUnsilence.setOnClickListener {
            if (notificationManager.isNotificationPolicyAccessGranted) {
                disableSilentMode()
            } else {
                requestDndPermission()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // After returning from DND settings, activate scheduling if permission was granted
        if (PrefsManager.isEnabled(this) && notificationManager.isNotificationPolicyAccessGranted) {
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
            val etAfter = rowView.findViewById<EditText>(R.id.etAfter)

            tvName.text = prayerNames[prayer] ?: prayer.name

            // Show today's prayer time
            val prayerTime = todayTimes?.allPrayers()?.find { it.prayer == prayer }
            tvTime.text = if (prayerTime != null) {
                String.format("%02d:%02d", prayerTime.hour, prayerTime.minute)
            } else {
                "--:--"
            }

            // Load saved values
            etAfter.setText(PrefsManager.getAfterMinutes(this, prayer).toString())

            // Save on change
            etAfter.addTextChangedListener(createSaveWatcher { text ->
                val minutes = text.toIntOrNull() ?: 0
                PrefsManager.setAfterMinutes(this, prayer, minutes)
                rescheduleIfEnabled()
            })

            container.addView(rowView)
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

    private fun updateUI() {
        val isSilent = audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT
        val hasDndAccess = notificationManager.isNotificationPolicyAccessGranted

        binding.tvStatus.text = when {
            !hasDndAccess -> getString(R.string.status_no_permission)
            isSilent -> getString(R.string.status_silent)
            else -> getString(R.string.status_normal)
        }

        binding.btnSilence.isEnabled = !isSilent || !hasDndAccess
        binding.btnUnsilence.isEnabled = isSilent && hasDndAccess
    }
}
