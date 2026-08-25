package com.fancyshark.wpdialer.call

import android.os.Build
import android.os.OutcomeReceiver
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.VideoProfile
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-neutral audio route. Fed by the [CallEndpoint] callbacks on
 * Android 14+ and by the legacy [CallAudioState] callback on Android 13
 * (the endpoint API doesn't exist there).
 */
enum class AudioRoute { EARPIECE, SPEAKER, BLUETOOTH, WIRED_HEADSET, STREAMING, UNKNOWN }

/**
 * Tracks the calls delivered by [WpInCallService]. Supports one primary call
 * plus a secondary (waiting or held) call: answer-and-hold, swap, and
 * reject-with-text.
 */
object CallManager {

    var service: WpInCallService? = null

    private val calls = mutableListOf<Call>()

    private val _call = MutableStateFlow<Call?>(null)
    val call: StateFlow<Call?> = _call

    private val _state = MutableStateFlow(Call.STATE_DISCONNECTED)
    val state: StateFlow<Int> = _state

    private val _secondCall = MutableStateFlow<Call?>(null)
    val secondCall: StateFlow<Call?> = _secondCall

    private val _secondState = MutableStateFlow(Call.STATE_DISCONNECTED)
    val secondState: StateFlow<Int> = _secondState

    private val _route = MutableStateFlow<AudioRoute?>(null)
    val route: StateFlow<AudioRoute?> = _route

    private val _availableRoutes = MutableStateFlow<Set<AudioRoute>>(emptySet())
    val availableRoutes: StateFlow<Set<AudioRoute>> = _availableRoutes

    // Raw endpoints kept only on 34+ — requestCallEndpointChange needs the
    // actual CallEndpoint object, not just its type.
    private var endpoints: List<CallEndpoint> = emptyList()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted

    // Telecom fills contactDisplayName/CNAP asynchronously after onCallAdded
    // and delivers it via onDetailsChanged — the Call reference in the flows
    // doesn't change, so UI reading telecomName() must key off this tick.
    private val _detailsTick = MutableStateFlow(0)
    val detailsTick: StateFlow<Int> = _detailsTick

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, newState: Int) = recompute()
        override fun onDetailsChanged(call: Call, details: Call.Details) {
            _detailsTick.value += 1
        }
    }

    fun stateOf(call: Call): Int = call.details.state

    /**
     * Telecom's own name for a call, used when our contacts lookup finds
     * nothing: the system's contact match first (works even where our query
     * fails, e.g. contacts permission denied to the app), then the
     * network-provided caller name (CNAP — mandated in e.g. India) when the
     * network allows presenting it.
     */
    fun telecomName(call: Call?): String? {
        val d = call?.details ?: return null
        d.contactDisplayName?.takeIf { it.isNotBlank() }?.let { return it }
        val cnap = d.callerDisplayName
        return if (!cnap.isNullOrBlank() &&
            d.callerDisplayNamePresentation == android.telecom.TelecomManager.PRESENTATION_ALLOWED
        ) {
            cnap
        } else {
            null
        }
    }

    /** Ranks calls so the most "active" one is primary. */
    private fun rank(call: Call): Int = when (stateOf(call)) {
        Call.STATE_ACTIVE -> 0
        Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_PULLING_CALL -> 1
        Call.STATE_HOLDING -> 2
        Call.STATE_RINGING -> 3
        else -> 4
    }

    private fun recompute() {
        // After a merge, telecom keeps the child calls in the list with a
        // conference parent — only top-level calls drive the UI.
        // Rank ties (e.g. two held calls) keep the current primary sticky —
        // otherwise holding the active call would flip the UI (and hangup/
        // unhold targets) to the OTHER held call.
        val current = _call.value
        val live = calls.filter { stateOf(it) != Call.STATE_DISCONNECTED && it.parent == null }
            .sortedWith(compareBy({ rank(it) }, { if (it === current) 0 else 1 }))
        // If only a ringing call exists it is primary (the incoming screen);
        // otherwise a ringing call behind an ongoing one is the second call.
        _call.value = live.firstOrNull()
        _state.value = live.firstOrNull()?.let { stateOf(it) } ?: Call.STATE_DISCONNECTED
        _secondCall.value = live.getOrNull(1)
        _secondState.value = live.getOrNull(1)?.let { stateOf(it) } ?: Call.STATE_DISCONNECTED
        if (live.isEmpty()) {
            // Per-call audio state must not leak into the next call. The
            // available endpoints stay: they describe device hardware, and
            // Telecom only re-delivers them when they actually change — a
            // back-to-back call within one service binding would otherwise
            // see an empty list and dead speaker/bluetooth controls.
            _route.value = null
            _muted.value = false
        }
    }

    /** True after the user backs out of the in-call UI to browse the app;
     *  suppresses the auto-return until the next call. */
    @Volatile
    var userDismissedUi = false

    fun onCallAdded(call: Call) {
        userDismissedUi = false
        if (calls.isEmpty()) {
            // A fresh call session: drop any per-call route state a late
            // callback repopulated after the previous session's reset (stale
            // speaker would also disable the proximity lock). Available
            // endpoints are device-level — kept (see recompute).
            _route.value = null
            _muted.value = false
        }
        if (call !in calls) {
            calls += call
            call.registerCallback(callback)
        }
        recompute()
    }

    fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        calls.remove(call)
        recompute()
    }

    /**
     * Full teardown when the in-call service is destroyed. If Telecom
     * unbinds without delivering per-call removals, stale (dead-binder)
     * calls would otherwise poison every later call session.
     */
    fun reset() {
        calls.forEach { runCatching { it.unregisterCallback(callback) } }
        calls.clear()
        dtmfCall = null
        // Full unbind: the next service binding re-delivers the endpoint
        // list, so here (unlike between back-to-back calls) it's safe to drop.
        endpoints = emptyList()
        _availableRoutes.value = emptySet()
        recompute()
    }

    @RequiresApi(34)
    private fun routeOf(endpointType: Int): AudioRoute = when (endpointType) {
        CallEndpoint.TYPE_EARPIECE -> AudioRoute.EARPIECE
        CallEndpoint.TYPE_SPEAKER -> AudioRoute.SPEAKER
        CallEndpoint.TYPE_BLUETOOTH -> AudioRoute.BLUETOOTH
        CallEndpoint.TYPE_WIRED_HEADSET -> AudioRoute.WIRED_HEADSET
        CallEndpoint.TYPE_STREAMING -> AudioRoute.STREAMING
        else -> AudioRoute.UNKNOWN
    }

    @RequiresApi(34)
    fun updateEndpoint(endpoint: CallEndpoint?) {
        _route.value = endpoint?.let { routeOf(it.endpointType) }
    }

    @RequiresApi(34)
    fun updateAvailableEndpoints(list: List<CallEndpoint>) {
        endpoints = list
        _availableRoutes.value = list.mapTo(mutableSetOf()) { routeOf(it.endpointType) }
    }

    /** Android 13 path: route, supported routes, and mute all arrive here. */
    fun updateAudioState(state: CallAudioState) {
        _route.value = when (state.route) {
            CallAudioState.ROUTE_SPEAKER -> AudioRoute.SPEAKER
            CallAudioState.ROUTE_BLUETOOTH -> AudioRoute.BLUETOOTH
            CallAudioState.ROUTE_WIRED_HEADSET -> AudioRoute.WIRED_HEADSET
            CallAudioState.ROUTE_EARPIECE -> AudioRoute.EARPIECE
            else -> AudioRoute.UNKNOWN
        }
        val mask = state.supportedRouteMask
        _availableRoutes.value = buildSet {
            if (mask and CallAudioState.ROUTE_EARPIECE != 0) add(AudioRoute.EARPIECE)
            if (mask and CallAudioState.ROUTE_SPEAKER != 0) add(AudioRoute.SPEAKER)
            if (mask and CallAudioState.ROUTE_BLUETOOTH != 0) add(AudioRoute.BLUETOOTH)
            if (mask and CallAudioState.ROUTE_WIRED_HEADSET != 0) add(AudioRoute.WIRED_HEADSET)
        }
        _muted.value = state.isMuted
    }

    fun updateMuted(muted: Boolean) {
        _muted.value = muted
    }

    fun answer() = ringingCall()?.answer(VideoProfile.STATE_AUDIO_ONLY)

    /** Answers the waiting call; Telecom holds the current one automatically. */
    fun answerWaiting() {
        _call.value?.takeIf { stateOf(it) == Call.STATE_ACTIVE }?.hold()
        ringingCall()?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun reject() = ringingCall()?.reject(false, null)

    fun rejectWithMessage(message: String) = ringingCall()?.reject(true, message)

    fun rejectWaiting() = ringingCall()?.reject(false, null)

    /** The ringing call, whether it's the primary or a waiting second call. */
    fun ringingCall(): Call? =
        calls.firstOrNull { stateOf(it) == Call.STATE_RINGING }

    fun hangup() = _call.value?.disconnect()

    /** Unholds the held call; Telecom holds the active one. */
    fun swap() {
        calls.firstOrNull { stateOf(it) == Call.STATE_HOLDING }?.unhold()
    }

    fun hasHeldSecondCall(): Boolean =
        _secondCall.value?.let { stateOf(it) == Call.STATE_HOLDING } == true

    fun setMuted(muted: Boolean) = service?.setMuted(muted)

    /**
     * Named Bluetooth devices for the in-call picker. Only the 34+ endpoint
     * API can address individual devices — on 33 setAudioRoute picks the
     * platform's active one, so this returns empty and the picker never shows.
     */
    fun bluetoothNames(): List<String> =
        if (Build.VERSION.SDK_INT >= 34) {
            endpoints.filter { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
                .map { it.endpointName.toString() }
        } else {
            emptyList()
        }

    /**
     * Routes to the Bluetooth device with this [bluetoothNames] entry.
     * Matched by name, not index — the endpoint list can change between the
     * picker rendering and the tap (an index would land on the wrong
     * device); a vanished name is a safe no-op.
     */
    fun selectBluetooth(name: String) {
        if (Build.VERSION.SDK_INT < 34) return
        endpoints.filter { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
            .firstOrNull { it.endpointName.toString() == name }
            ?.let { requestRoute(it) }
    }

    fun setSpeaker(on: Boolean) =
        setRoute(on, CallEndpoint.TYPE_SPEAKER, CallAudioState.ROUTE_SPEAKER)

    fun setBluetooth(on: Boolean) =
        setRoute(on, CallEndpoint.TYPE_BLUETOOTH, CallAudioState.ROUTE_BLUETOOTH)

    private fun setRoute(on: Boolean, endpointType: Int, legacyRoute: Int) {
        if (Build.VERSION.SDK_INT >= 34) {
            requestRoute(
                if (on) {
                    endpoints.firstOrNull { it.endpointType == endpointType }
                } else {
                    defaultRoute()
                },
            )
        } else {
            setAudioRouteCompat(
                if (on) legacyRoute else CallAudioState.ROUTE_WIRED_OR_EARPIECE,
            )
        }
    }

    // Android 13: setAudioRoute is deprecated (34+) but the only routing API
    // there; WIRED_OR_EARPIECE lets the platform pick wired when present.
    @Suppress("DEPRECATION")
    private fun setAudioRouteCompat(route: Int) {
        service?.setAudioRoute(route)
    }

    @RequiresApi(34)
    private fun defaultRoute(): CallEndpoint? =
        endpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_WIRED_HEADSET }
            ?: endpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_EARPIECE }

    @RequiresApi(34)
    private fun requestRoute(target: CallEndpoint?) {
        val svc = service ?: return
        if (target == null) return
        svc.requestCallEndpointChange(
            target,
            svc.mainExecutor,
            object : OutcomeReceiver<Void, CallEndpointException> {
                override fun onResult(result: Void?) {}
            },
        )
    }

    fun setHold(on: Boolean) {
        val c = _call.value ?: return
        if (on) c.hold() else c.unhold()
    }

    /** Merge the active and held calls into a conference (if supported). */
    fun merge() {
        val active = _call.value ?: return
        val held = _secondCall.value ?: return
        runCatching { active.conference(held) }
    }

    // The stop must go to the same call the tone started on — resolving
    // _call.value twice lets a mid-press primary swap latch the tone.
    private var dtmfCall: Call? = null

    fun startDtmf(digit: Char) {
        val c = _call.value ?: return
        dtmfCall = c
        runCatching { c.playDtmfTone(digit) }
    }

    fun stopDtmf() {
        dtmfCall?.let { runCatching { it.stopDtmfTone() } }
        dtmfCall = null
    }
}
