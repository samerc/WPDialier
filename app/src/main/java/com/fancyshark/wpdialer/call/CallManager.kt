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

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, newState: Int) = recompute()
    }

    fun stateOf(call: Call): Int = call.details.state

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
            // Audio-route state must not leak into the next call.
            _route.value = null
            _availableRoutes.value = emptySet()
            endpoints = emptyList()
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
            // A fresh call session: drop any audio-route state a late
            // endpoint callback repopulated after the previous session's
            // reset (stale speaker would also disable the proximity lock).
            _route.value = null
            _availableRoutes.value = emptySet()
            endpoints = emptyList()
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

    fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 34) {
            requestRoute(
                if (on) {
                    endpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_SPEAKER }
                } else {
                    defaultRoute()
                },
            )
        } else {
            setAudioRouteCompat(
                if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE,
            )
        }
    }

    fun setBluetooth(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 34) {
            requestRoute(
                if (on) {
                    endpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
                } else {
                    defaultRoute()
                },
            )
        } else {
            setAudioRouteCompat(
                if (on) CallAudioState.ROUTE_BLUETOOTH else CallAudioState.ROUTE_WIRED_OR_EARPIECE,
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
