package com.tempmail.otp.util

/**
 * Whether the UI is currently visible. The clipboard may only be written while the app is
 * in the foreground (Android 10+), so the ViewModel checks this before copying an OTP.
 */
object AppVisibility {

    @Volatile
    var isForeground: Boolean = false
}
