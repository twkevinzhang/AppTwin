import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.android.tools.build:apksig:8.13.2")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    providers.systemProperty("pinnedMicrogApk").orNull?.let {
        systemProperty("pinnedMicrogApk", it)
    }
    providers.systemProperty("pinnedMicrogCompanionApk").orNull?.let {
        systemProperty("pinnedMicrogCompanionApk", it)
    }
}

data class PinnedBuildArtifact(
    val fileName: String,
    val sha256: String,
    val downloadUrl: String,
    val propertyName: String,
)

private val pinnedArtifacts = listOf(
    PinnedBuildArtifact(
        fileName = "com.google.android.gms-250932030.apk",
        sha256 = "52597e77fd25fdd347574d0457ed1936a4b9561cf4c8d34e7ac8dd8191dfd4b9",
        downloadUrl = "https://github.com/microg/GmsCore/releases/download/" +
            "v0.3.15.250932/com.google.android.gms-250932030.apk",
        propertyName = "microgArtifactFile",
    ),
    PinnedBuildArtifact(
        fileName = "com.android.vending-84022630.apk",
        sha256 = "a973e0235a2829773a4faf36d235d5f703d1c04a2adff674ebaa535a2e78f937",
        downloadUrl = "https://github.com/microg/GmsCore/releases/download/" +
            "v0.3.15.250932/com.android.vending-84022630.apk",
        propertyName = "microgCompanionArtifactFile",
    ),
)

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Prepares the two reviewed artifacts without committing either APK to Git.
 *
 * Callers may pass both explicit properties. If only one is provided, its directory is also
 * checked for the other fixed filename. Without local input, only immutable official URLs are
 * used; a mutable latest release or caller-provided URL is never accepted.
 */
tasks.register("preparePinnedMicrogArtifact") {
    group = "verification"
    description = "Prepare and verify the pinned microG v0.3.15.250932 release pair"
    val outputRoot = layout.buildDirectory.dir("local-cache")
    outputs.files(pinnedArtifacts.map { outputRoot.map { root -> root.file(it.fileName) } })
    doLast {
        val explicit = pinnedArtifacts.associateWith { artifact ->
            providers.gradleProperty(artifact.propertyName).orNull?.let(::File)
        }
        val suppliedDirectory = explicit.values.filterNotNull().singleOrNull()?.parentFile

        pinnedArtifacts.forEach { artifact ->
            val destination = outputRoot.get().file(artifact.fileName).asFile
            if (destination.isFile && sha256(destination) == artifact.sha256) {
                logger.lifecycle("Pinned ${artifact.fileName} is already prepared and verified.")
                return@forEach
            }
            val direct = explicit[artifact]
            val sibling = suppliedDirectory?.resolve(artifact.fileName)?.takeIf(File::isFile)
            val local = direct ?: sibling
            if (direct != null && !direct.isFile) {
                throw GradleException("Pinned local artifact is missing: ${artifact.fileName}")
            }
            if (gradle.startParameter.isOffline && local == null) {
                throw GradleException(
                    "Pinned ${artifact.fileName} is unavailable in offline mode. " +
                        "Provide -P${artifact.propertyName}=/absolute/path/${artifact.fileName}",
                )
            }
            destination.parentFile.mkdirs()
            val temporary = File(destination.parentFile, ".${destination.name}.partial")
            temporary.delete()
            try {
                if (local != null) {
                    local.inputStream().use { input -> temporary.outputStream().use(input::copyTo) }
                } else {
                    val connection = URI(artifact.downloadUrl).toURL().openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 60_000
                    connection.setRequestProperty("User-Agent", "AppTwin-pinned-artifact-preparer/2")
                    try {
                        if (connection.responseCode !in 200..299) {
                            throw GradleException(
                                "Official pinned artifact download failed: HTTP ${connection.responseCode}",
                            )
                        }
                        connection.inputStream.use { input ->
                            temporary.outputStream().use(input::copyTo)
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
                val actual = sha256(temporary)
                if (actual != artifact.sha256) {
                    throw GradleException(
                        "Pinned ${artifact.fileName} SHA-256 mismatch: " +
                            "expected=${artifact.sha256} actual=$actual",
                    )
                }
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                logger.lifecycle("Prepared pinned ${artifact.fileName} (SHA-256 verified).")
            } finally {
                temporary.delete()
            }
        }
    }
}
