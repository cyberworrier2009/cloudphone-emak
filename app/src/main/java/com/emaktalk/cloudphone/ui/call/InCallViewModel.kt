package com.emaktalk.cloudphone.ui.call

import androidx.lifecycle.ViewModel
import com.emaktalk.cloudphone.sip.SipCoreManager

class InCallViewModel : ViewModel() {

    val callState = SipCoreManager.callState

    fun answer() = SipCoreManager.acceptCall()
    fun hangUp() = SipCoreManager.terminateCall()
    fun toggleMute() = SipCoreManager.toggleMute()
    fun toggleSpeaker() = SipCoreManager.toggleSpeaker()
    fun sendDtmf(digit: Char) = SipCoreManager.sendDtmf(digit)
}
