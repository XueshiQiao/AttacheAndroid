package org.attache.tailscale

import timber.log.Timber

/**
 * JNI bridge to the embedded Go `tsnet` shared library (`libattache_tsnet.so`).
 */
object TsnetNative {
    val isAvailable: Boolean = try {
        System.loadLibrary("attache_tsnet")
        true
    } catch (t: Throwable) {
        Timber.w(t, "libattache_tsnet.so is not available on this ABI/runtime")
        false
    }

    @JvmStatic
    external fun nativeStart(
        stateDir: String,
        hostname: String,
        authKey: String,
        controlUrl: String,
    ): String

    @JvmStatic
    external fun nativeStop()

    @JvmStatic
    external fun nativeStartLoginInteractive(): String

    @JvmStatic
    external fun nativeLogout(): String

    @JvmStatic
    external fun nativeStatusJson(): String

    @JvmStatic
    external fun nativeOpenTcpForwarder(targetHost: String, targetPort: Int): Int

    @JvmStatic
    external fun nativeCloseTcpForwarder(localPort: Int)

    @JvmStatic
    external fun nativeSetNetworkHandle(
        networkHandle: Long,
        defaultIfName: String,
        defaultGateway: String,
    )

    @JvmStatic
    external fun nativeUpdateInterfaces(spec: String)
}
