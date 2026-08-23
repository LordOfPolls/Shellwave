package io.github.lordofpolls.shellwave

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import io.github.lordofpolls.shellwave.core.net.HostReachabilityProbe
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import javax.inject.Inject

/**
 * Android ships a stripped-down "BC" security provider. sshj needs the full BouncyCastle
 * implementation, so it must be swapped in before any sshj call is made: this is the single most
 * common way sshj-on-Android goes wrong.
 *
 * Also where `HostReachabilityProbe` is bound to the process lifecycle. It has to be here and not
 * in an activity or a composable: `ProcessLifecycleOwner` is the only observer that distinguishes
 * "the user left the app" from "the screen rotated", and probing must stop for the first and
 * survive the second.
 */
@HiltAndroidApp
class ShellwaveApplication : Application() {

    @Inject
    lateinit var reachabilityProbe: HostReachabilityProbe

    override fun onCreate() {
        super.onCreate()
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    reachabilityProbe.start()
                }

                override fun onStop(owner: LifecycleOwner) {
                    reachabilityProbe.stop()
                }
            },
        )
    }
}
