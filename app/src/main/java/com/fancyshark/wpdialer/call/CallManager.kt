package com.fancyshark.wpdialer.call

import android.os.OutcomeReceiver
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.VideoProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks the calls delivered by [WpInCallService]. Supports one primary call
 * plus a secondary (waiting or held) call: answer-and-hold, swap, and
 * reject-with-text. Audio routing uses the Android 14+ [CallEndpoint] API.
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

    private val _endpoint = MutableStateFlow<CallEndpoint?>(null)
    val endpoint: StateFlow<CallEndpoint?> = _endpoint

    private val _availableEndpoints = MutableStateFlow<List<CallEndpoint>>(emptyList())
    val availableEndpoints: StateFlow<List<CallEndpoint>> = _availableEndpoints

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
        val live = calls.filter { stateOf(it) != Call.STATE_DISCONNECTED }
            .sortedBy { rank(it) }
        // If only a ringing call exists it is primary (the incoming screen);
        // otherwise a ringing call behind an ongoing one is the second call.
        _call.value = live.firstOrNull()
        _state.value = live.firstOrNull()?.let { stateOf(it) } ?: Call.STATE_DISCONNECTED
        _secondCall.value = live.getOrNull(1)
        _secondState.value = live.getOrNull(1)?.let { stateOf(it) } ?: Call.STATE_DISCONNECTED
    }

    fun onCallAdded(call: Call) {
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

    fun updateEndpoint(endpoint: CallEndpoint?) {
        _endpoint.value = endpoint
    }

    fun updateAvailableEndpoints(endpoints: List<CallEndpoint>) {
        _availableEndpoints.value = endpoints
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

    private fun ringingCall(): Call? =
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
        val available = _availableEndpoints.value
        requestRoute(
            if (on) {
                available.firstOrNull { it.endpointType == CallEndpoint.TYPE_SPEAKER }
            } else {
                defaultRoute()
            },
        )
    }

    fun setBluetooth(on: Boolean) {
        val available = _availableEndpoints.value
        requestRoute(
            if (on) {
                available.firstOrNull { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
            } else {
                defaultRoute()
            },
        )
    }

    private fun defaultRoute(): CallEndpoint? {
        val available = _availableEndpoints.value
        return available.firstOrNull { it.endpointType == CallEndpoint.TYPE_WIRED_HEADSET }
            ?: available.firstOrNull { it.endpointType == CallEndpoint.TYPE_EARPIECE }
    }

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

    fun startDtmf(digit: Char) = _call.value?.playDtmfTone(digit)

    fun stopDtmf() = _call.value?.stopDtmfTone()
}
