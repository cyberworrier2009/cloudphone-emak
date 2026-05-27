package com.emaktalk.cloudphone

import android.app.Application
import com.emaktalk.cloudphone.sip.SipCoreManager

class CloudPhoneApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SipCoreManager.initialize(this)
    }
}
