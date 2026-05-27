package com.emaktalk.cloudphone.ui.dialer

import androidx.lifecycle.ViewModel
import com.emaktalk.cloudphone.sip.SipCoreManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DialerViewModel : ViewModel() {

    private val _number = MutableStateFlow("")
    val number: StateFlow<String> = _number.asStateFlow()

    val registrationState = SipCoreManager.registrationState
    val callState = SipCoreManager.callState
    val callError = SipCoreManager.callError

    fun clearCallError() = SipCoreManager.clearCallError()

    fun onKeyPress(digit: Char) {
        _number.value += digit
        SipCoreManager.playKeypadTone(digit)
    }

    fun onZeroLongPress() {
        // Replace a trailing '0' (just typed by the click) with '+'.
        _number.value = _number.value.dropLast(1) + "+"
    }

    fun onBackspace() {
        _number.value = _number.value.dropLast(1)
    }

    fun onClear() {
        _number.value = ""
    }

    fun onCall() {
        val target = _number.value.trim()
        if (target.isNotEmpty()) {
            SipCoreManager.startCall(target)
        }
    }
}
