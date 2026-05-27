package com.emaktalk.cloudphone.ui.account

import androidx.lifecycle.ViewModel
import com.emaktalk.cloudphone.sip.SipCoreManager
import org.linphone.core.TransportType

class AccountViewModel : ViewModel() {

    val registrationState = SipCoreManager.registrationState
    val registrationMessage = SipCoreManager.registrationMessage

    fun register(username: String, password: String, domain: String, transport: TransportType) {
        if (username.isBlank() || domain.isBlank()) return
        SipCoreManager.register(
            username = username.trim(),
            password = password,
            domain = domain.trim(),
            transport = transport
        )
    }

    fun unregister() = SipCoreManager.unregister()
}
