package com.tunisianprayertimes.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tunisianprayertimes.OfficialIslamicDates
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.chrono.HijrahChronology
import java.time.temporal.ChronoField
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LoadOfficialCalendarYear(year: Int) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(year, context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val requests = Channel<Unit>(Channel.CONFLATED)
            val forceNextRefresh = AtomicBoolean(false)
            fun requestRefresh(force: Boolean) {
                // A periodic tick must not replace a pending reconnect/resume request.
                if (force) forceNextRefresh.set(true)
                requests.trySend(Unit)
            }
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) requestRefresh(force = true)
            }
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val callback = object : ConnectivityManager.NetworkCallback() {
                private var validatedNetwork: Network? = null

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (online && validatedNetwork != network) {
                        validatedNetwork = network
                        requestRefresh(force = true)
                    } else if (!online && validatedNetwork == network) {
                        validatedNetwork = null
                    }
                }

                override fun onLost(network: Network) {
                    if (validatedNetwork == network) validatedNetwork = null
                }
            }
            var callbackRegistered = false
            var periodicRefresh: Job? = null
            var refreshConsumer: Job? = null
            try {
                lifecycle.addObserver(observer)
                callbackRegistered = runCatching {
                    if (connectivity == null) false else {
                        connectivity.registerDefaultNetworkCallback(callback)
                        true
                    }
                }.getOrDefault(false)
                requestRefresh(force = true)
                periodicRefresh = launch {
                    while (isActive) {
                        delay(60_000L)
                        requestRefresh(force = false)
                    }
                }
                refreshConsumer = launch {
                    for (request in requests) {
                        val refresh = forceNextRefresh.getAndSet(false)
                        withContext(Dispatchers.IO) {
                            val supportedYears = HijrahChronology.INSTANCE.range(ChronoField.YEAR)
                            // Shared provider throttles successful loads and deduplicates
                            // row/dialog requests; estimates/cache remain visible.
                            for (candidate in listOf(year, year - 1, year + 1)) {
                                currentCoroutineContext().ensureActive()
                                if (supportedYears.isValidValue(candidate.toLong())) {
                                    OfficialIslamicDates.loadYear(candidate, refresh = refresh)
                                }
                            }
                        }
                    }
                }
                // Unregister immediately on stop/disposal, even if the consumer's
                // synchronous network call still needs to finish its timeout.
                awaitCancellation()
            } finally {
                periodicRefresh?.cancel()
                refreshConsumer?.cancel()
                lifecycle.removeObserver(observer)
                if (callbackRegistered) runCatching { connectivity?.unregisterNetworkCallback(callback) }
                requests.close()
            }
        }
    }
}

/** Updates at local midnight, after clock/time-zone changes, and when returning to the app. */
@Composable
internal fun rememberCalendarToday(): LocalDate {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val changes = Channel<Unit>(Channel.CONFLATED)
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) changes.trySend(Unit)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    changes.trySend(Unit)
                }
            }
            var receiverRegistered = false
            var midnightRefresh: Job? = null
            try {
                lifecycle.addObserver(observer)
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_DATE_CHANGED)
                        addAction(Intent.ACTION_TIME_CHANGED)
                        addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    },
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                receiverRegistered = true
                changes.trySend(Unit)
                for (change in changes) {
                    val now = ZonedDateTime.now()
                    today = now.toLocalDate()
                    midnightRefresh?.cancel()
                    midnightRefresh = launch {
                        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
                        delay(Duration.between(now, midnight).toMillis().coerceAtLeast(1L))
                        changes.trySend(Unit)
                    }
                }
            } finally {
                midnightRefresh?.cancel()
                lifecycle.removeObserver(observer)
                if (receiverRegistered) context.unregisterReceiver(receiver)
                changes.close()
            }
        }
    }
    return today
}
