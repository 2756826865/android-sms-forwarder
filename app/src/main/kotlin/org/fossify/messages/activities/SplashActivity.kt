package org.fossify.messages.activities

import android.content.Intent
import android.os.Bundle
import org.fossify.commons.activities.BaseSplashActivity
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.SIDELOADING_FALSE

class SplashActivity : BaseSplashActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // This is an authorized, independently signed fork.  Fossify Commons caches
        // its sideload verdict and would otherwise show the upstream "fake version"
        // warning on every entry point that starts through BaseSplashActivity.
        applicationContext.baseConfig.appSideloadingStatus = SIDELOADING_FALSE
        super.onCreate(savedInstanceState)
    }

    override fun initActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
