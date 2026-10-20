package dev.ahmed.savlino.jobs

/** Storage tests own their journals. Startup is exercised explicitly in coordinator/worker tests. */
class IsolatedTestApplication : NezzelApplication() {
    var coordinatorOverride: DownloadCoordinator? = null
    override val downloads: DownloadCoordinator get() = coordinatorOverride ?: super.downloads
    var maintenanceRequests = 0
        private set
    override fun startMaintenanceAfterOnboarding() { maintenanceRequests++ }
    override fun onCreate() {
        // Most Activity tests exercise an established Home session; first-run tests explicitly clear this.
        dev.ahmed.savlino.locale.AppPreferences(this).markOnboardingSeen()
        // Do not start process-wide recovery against tests' private staging.
        // Robolectric has no AndroidKeyStore: a fixed fake key stands in for the per-install HMAC key.
        dev.ahmed.savlino.storage.KeystoreLinkKeyer.testOverride = { FAKE_KEYER }
    }
    companion object { val FAKE_KEYER = dev.ahmed.savlino.domain.LinkKeyer.withSecret(ByteArray(32) { it.toByte() }) }
}
