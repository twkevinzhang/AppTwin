package org.apptwin.fixture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentProbeTest {
    @Test
    fun `reads real uid from proc status without retaining status contents`() {
        assertEquals(
            10311,
            EnvironmentProbeLogic.readProcRealUid(
                "Name:\tguest\nUid:\t10311\t10311\t10311\t10311\nGid:\t10311\t10311\t10311\t10311\n",
            ),
        )
        assertEquals(null, EnvironmentProbeLogic.readProcRealUid("Name:\tguest\n"))
    }


    @Test
    fun classifyCmdline_prefersExactGuestWhenHostIsPackagePrefix() {
        assertEquals(
            PathOwner.GUEST,
            EnvironmentProbeLogic.classifyCmdline(
                "org.apptwin.fixture\u0000ignored",
                "org.apptwin.fixture",
                setOf("org.apptwin"),
            ),
        )
    }

    @Test
    fun classifyCmdline_detectsHostProcessSuffix() {
        assertEquals(
            PathOwner.HOST,
            EnvironmentProbeLogic.classifyCmdline(
                "org.apptwin:p0\u0000",
                "com.shopee.tw",
                setOf("org.apptwin"),
            ),
        )
    }
    @Test
    fun `classifies guest host other and unavailable paths without returning paths`() {
        val hostPackages = setOf("com.example.clonehost")

        assertEquals(
            PathOwner.GUEST,
            EnvironmentProbeLogic.classifyPath(
                "/data/user/0/org.apptwin.fixture/files",
                "org.apptwin.fixture",
                hostPackages,
            ),
        )
        assertEquals(
            PathOwner.HOST,
            EnvironmentProbeLogic.classifyPath(
                "/data/app/~~salt/com.example.clonehost-hash/base.apk",
                "org.apptwin.fixture",
                hostPackages,
            ),
        )
        assertEquals(
            PathOwner.OTHER,
            EnvironmentProbeLogic.classifyPath(
                "/system/framework/framework.jar",
                "org.apptwin.fixture",
                hostPackages,
            ),
        )
        assertEquals(
            PathOwner.UNAVAILABLE,
            EnvironmentProbeLogic.classifyPath(null, "org.apptwin.fixture", hostPackages),
        )
    }

    @Test
    fun `detects only configured host package tokens in proc-like content`() {
        val hostPackages = setOf("com.example.clonehost")

        assertTrue(
            EnvironmentProbeLogic.textContainsAnyToken(
                "com.example.clonehost:p0\u0000",
                hostPackages,
            ),
        )
        assertFalse(
            EnvironmentProbeLogic.textContainsAnyToken(
                "/data/user/0/org.apptwin.fixture/base.apk",
                hostPackages,
            ),
        )

        val procFixture = File.createTempFile("environment-probe", ".maps")
        try {
            procFixture.writeText("7f000 /data/app/com.example.clonehost/lib/arm64/libhost.so\n")
            assertTrue(EnvironmentProbeLogic.fileContainsAnyToken(procFixture, hostPackages))
        } finally {
            assertTrue(procFixture.delete())
        }
    }

    @Test
    fun `hashes identifiers to a stable short sha256 prefix`() {
        val rawAndroidId = "sensitive-android-id"

        val hash = EnvironmentProbeLogic.sha256Prefix(rawAndroidId)

        assertEquals("21646419cf35", hash)
        assertEquals(12, hash.length)
        assertFalse(hash.contains(rawAndroidId))
    }

    @Test
    fun `serialized snapshot contains no raw android id paths or proc content`() {
        val rawAndroidId = "raw-android-id-must-not-leak"
        val rawDataPath = "/data/user/0/com.example.clonehost/secrets"
        val rawProcLine = "com.example.clonehost:p0 secret-token"
        val snapshot = EnvironmentProbeSnapshot(
            packageName = "org.apptwin.fixture",
            processUid = 10123,
            packageUid = 10123,
            processPackageUidConsistent = true,
            processProcUidConsistent = true,
            packageProcUidConsistent = true,
            dataDirOwner = PathOwner.HOST,
            sourceDirOwner = PathOwner.GUEST,
            uidPackagesContainsGuest = true,
            uidPackagesContainsHost = true,
            androidIdSha256Prefix = EnvironmentProbeLogic.sha256Prefix(rawAndroidId),
            buildManufacturer = "manufacturer",
            buildBrand = "brand",
            buildModel = "model\nwith-newline",
            buildDevice = "device",
            buildProduct = "product",
            buildSdk = 35,
            webViewProvider = "com.android.webview",
            gmsPackageAvailable = false,
            installerPackage = "com.android.packageinstaller",
            initiatingPackage = null,
            selfSignatureSha256Prefix = "0123456789ab",
            procCmdlineOwner = PathOwner.HOST,
            procMapsLeaksHostPackage = true,
        )

        val json = snapshot.toJson()

        assertTrue(json.contains("\"androidIdSha256Prefix\":\""))
        assertTrue(json.contains("\"dataDirOwner\":\"HOST\""))
        assertTrue(json.contains("\"procMapsLeaksHostPackage\":true"))
        assertTrue(json.contains("model\\nwith-newline"))
        assertFalse(json.contains(rawAndroidId))
        assertFalse(json.contains(rawDataPath))
        assertFalse(json.contains(rawProcLine))
        assertFalse(json.contains("secret-token"))
    }
}
