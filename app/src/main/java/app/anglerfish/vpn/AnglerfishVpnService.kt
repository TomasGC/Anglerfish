package app.anglerfish.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.core.app.NotificationCompat
import app.anglerfish.AnglerfishApplication
import app.anglerfish.R
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
import kotlin.concurrent.thread

// Scoped to whichever apps are currently selected via addAllowedApplication -- every other app
// keeps normal connectivity. The interface is a pure black hole: packets read from it are
// discarded and nothing is written back, which the blocked apps' network stacks observe
// identically to "no internet". No packet parsing/forwarding -- this is a dead end, not a proxy.
//
// Adapted from a read-only reference in the Raven repo (app.raven.vpn.AdBlockVpnService) --
// no Raven code is imported. The one structural difference: the allow-list is rebuilt from the
// current selection on every start, instead of a single hardcoded target package.
class AnglerfishVpnService : VpnService() {

    private var descriptor: ParcelFileDescriptor? = null

    @Volatile
    private var running = false

    private var drainThread: Thread? = null

    private val repository by lazy { (application as AnglerfishApplication).container.appRepository }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DEACTIVATE) {
            stopSelf()
            return START_NOT_STICKY
        }

        val selectedPackages = intent?.getStringArrayListExtra(EXTRA_SELECTED_PACKAGES)?.toSet().orEmpty()
        if (selectedPackages.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        teardownTunnel()

        val newDescriptor = buildTunnel(selectedPackages)
        if (newDescriptor == null) {
            Toast.makeText(this, getString(R.string.activate_failed), Toast.LENGTH_LONG).show()
            runBlocking { repository.setActive(false) }
            stopSelf()
            return START_NOT_STICKY
        }

        descriptor = newDescriptor
        running = true
        drainThread = thread(name = "AnglerfishVpnDrain") { drain(newDescriptor) }
        return START_STICKY
    }

    override fun onDestroy() {
        teardownTunnel()
        super.onDestroy()
    }

    // The system calls this when another app takes over the VPN slot, or the user revokes
    // consent from system settings while we're active -- tear down the same way onDestroy does
    // and reflect it in the persisted state so the UI switch flips back to inactive.
    override fun onRevoke() {
        teardownTunnel()
        runBlocking { repository.setActive(false) }
        stopSelf()
        super.onRevoke()
    }

    private fun buildTunnel(selectedPackages: Set<String>): ParcelFileDescriptor? {
        val builder = Builder()
            .addAddress(TUNNEL_ADDRESS, TUNNEL_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)
            .setSession(SESSION_NAME)

        selectedPackages.forEach { packageName ->
            try {
                builder.addAllowedApplication(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                // Stored selection can outlive an uninstall -- skip the stale entry instead of
                // failing the whole tunnel for every other selected app.
            }
        }

        return builder.establish()
    }

    private fun drain(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val buffer = ByteArray(PACKET_BUFFER_SIZE)
        while (running) {
            if (input.read(buffer) < 0) break
        }
    }

    private fun teardownTunnel() {
        running = false
        drainThread?.join(DRAIN_JOIN_TIMEOUT_MS)
        drainThread = null
        descriptor?.close()
        descriptor = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.vpn_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val deactivateIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AnglerfishVpnService::class.java).setAction(ACTION_DEACTIVATE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setSmallIcon(R.drawable.ic_anglerfish_notification)
            .setOngoing(true)
            .addAction(0, getString(R.string.vpn_notification_deactivate), deactivateIntent)
            .build()
    }

    companion object {
        const val EXTRA_SELECTED_PACKAGES = "app.anglerfish.vpn.EXTRA_SELECTED_PACKAGES"
        const val ACTION_DEACTIVATE = "app.anglerfish.vpn.ACTION_DEACTIVATE"
        private const val TUNNEL_ADDRESS = "10.0.0.2"
        private const val TUNNEL_PREFIX_LENGTH = 32
        private const val SESSION_NAME = "Anglerfish"
        private const val PACKET_BUFFER_SIZE = 32767
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "anglerfish_vpn"
        private const val DRAIN_JOIN_TIMEOUT_MS = 500L
    }
}
