package org.apptwin.runtime

import android.content.Intent
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.os.Parcel
import android.os.Parcelable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VPackageManager
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Validates the bridge's wire request against the actual installed pinned microG classes. */
@RunWith(AndroidJUnit4::class)
class OwnedGoogleCredentialProtocolInstrumentedTest {
    @Test
    fun pinnedMicrogDeserializesExactGoogleRequestIncludingNullableFields() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val activityClient = Class.forName("android.app.ActivityClient")
            assertNotNull(activityClient.getMethod("getInstance").invoke(null))
            assertEquals(java.lang.Boolean.TYPE, activityClient.getMethod("finishActivity",
                android.os.IBinder::class.java, Integer.TYPE, Intent::class.java, Integer.TYPE).returnType)
        }
        val installed = requireNotNull(VirtualCore.get().getInstalledAppInfo(GMS, 0)) {
            "Pinned virtual microG prerequisite missing"
        }
        assertFalse("Protocol must use virtual archive", installed.dependSystem)
        val bridge = Class.forName("com.lody.virtual.client.hook.proxies.credential.SpaceGoogleCredentialBridge")
        val loaderFactory = bridge.getDeclaredMethod("createPinnedLoader", String::class.java).apply { isAccessible = true }
        val loader = loaderFactory.invoke(null, requireNotNull(installed.apkPath)) as ClassLoader
        assertSame("microG request class must be loaded from the isolated archive", loader,
            loader.loadClass("com.google.android.gms.auth.api.signin.GoogleSignInOptions").classLoader)
        assertSame("microG safeparcel base must not resolve to the host GMS dependency", loader,
            loader.loadClass("com.google.android.gms.common.internal.safeparcel.AbstractSafeParcelable").classLoader)
        val create = bridge.getDeclaredMethod("createSignInIntent", ClassLoader::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java).apply { isAccessible = true }
        val caller = "org.apptwin.fixture"
        val clientId = "owned-protocol.apps.googleusercontent.com"
        listOf(UUID.randomUUID().toString() to "owned-fixture.invalid", null to null).forEach { (nonce, domain) ->
            val intent = create.invoke(null, loader, caller, clientId, nonce, domain) as Intent
            assertEquals("com.google.android.gms.auth.GOOGLE_SIGN_IN", intent.action)
            val component = ComponentName(GMS, "org.microg.gms.auth.signin.AuthSignInActivity")
            assertEquals(component, intent.component)
            val users = installed.installedUsers
            val user = requireNotNull(users.firstOrNull { it != 0 } ?: users.firstOrNull()) {
                "Pinned microG must be installed in a virtual Space"
            }
            val activity = requireNotNull(VPackageManager.get().getActivityInfo(component, 0, user))
            assertEquals(GMS, activity.packageName)
            assertEquals(component.className, activity.name)
            assertTrue("Result-returning pinned Activity must be exported", activity.exported)
            assertTrue(activity.enabled)
            assertEquals("singleTask would discard the request result", ActivityInfo.LAUNCH_MULTIPLE, activity.launchMode)
            val transferred = roundTrip(intent, loader)
            assertEquals(component, transferred.component)
            assertEquals(intent.action, transferred.action)
            assertEquals(nonce, transferred.getStringExtra("nonce"))
            @Suppress("DEPRECATION")
            val configuration = requireNotNull(transferred.getParcelableExtra<Parcelable>("config"))
            assertSame(loader, configuration.javaClass.classLoader)
            assertEquals("com.google.android.gms.auth.api.signin.internal.SignInConfiguration", configuration.javaClass.name)
            assertEquals(caller, read(configuration, "getPackageName"))
            val options = requireNotNull(read(configuration, "getOptions"))
            assertSame("Nested options must deserialize with the pinned loader", loader, options.javaClass.classLoader)
            assertEquals("com.google.android.gms.auth.api.signin.GoogleSignInOptions", options.javaClass.name)
            assertEquals(true, read(options, "isIdTokenRequested"))
            assertEquals(clientId, read(options, "getServerClientId"))
            assertEquals(domain, read(options, "getHostedDomain"))
        }
    }

    private fun roundTrip(intent: Intent, loader: ClassLoader): Intent {
        val parcel = Parcel.obtain()
        return try {
            intent.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Intent.CREATOR.createFromParcel(parcel).apply { setExtrasClassLoader(loader) }
        } finally { parcel.recycle() }
    }
    private fun read(value: Any, method: String): Any? = value.javaClass.getMethod(method).invoke(value)
    private companion object { const val GMS = "com.google.android.gms" }
}
