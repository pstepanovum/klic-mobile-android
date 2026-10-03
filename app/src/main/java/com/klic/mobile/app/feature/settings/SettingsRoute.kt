package com.klic.mobile.app.feature.settings

internal sealed class SettingsRoute {
    object Main : SettingsRoute()
    object Appearance : SettingsRoute()
    object AutoNightMode : SettingsRoute()
    object Updates : SettingsRoute()
    object Privacy : SettingsRoute()
    object Notifications : SettingsRoute()
    object DataStorage : SettingsRoute()
    // v0.5.3
    object PrivacyBlocked : SettingsRoute()
    object PrivacyAppLock : SettingsRoute()
    object PrivacyPasskeys : SettingsRoute()
    // v0.6.0 (§18.2)
    object PrivacyChangePassword : SettingsRoute()
    object PrivacyRecoveryEmail : SettingsRoute()
    object Language : SettingsRoute()
    object QrCode : SettingsRoute()
    object RecentCalls : SettingsRoute()
    // v0.5.5
    object ChatTheme : SettingsRoute()
    // v0.5.7 (§14.4)
    object SavedMessages : SettingsRoute()
    // Legal
    object PrivacyPolicy : SettingsRoute()
    object Terms : SettingsRoute()
}
