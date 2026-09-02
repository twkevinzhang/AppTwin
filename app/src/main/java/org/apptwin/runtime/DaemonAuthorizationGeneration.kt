package org.apptwin.runtime

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Invalidates a visible-session daemon authorization before durable Group/GMS scope changes.
 *
 * The token is deliberately opaque. It only needs to distinguish mutations that occur while the
 * host process remains alive; after a process restart there is no cached visible authorization and
 * the durable repositories are read again before the fast path can be used.
 */
internal object DaemonAuthorizationGeneration {
    private const val FILE_NAME = "daemon_authorization_generation"
    private const val INITIAL_TOKEN = "initial"

    private var cachedRoot: String? = null
    private var cachedToken: String? = null

    @Synchronized
    fun current(filesRoot: File): String? {
        val canonicalRoot = runCatching { filesRoot.canonicalPath }.getOrNull() ?: return null
        if (cachedRoot == canonicalRoot) return cachedToken
        val file = File(filesRoot, FILE_NAME)
        val token = if (!file.exists()) {
            INITIAL_TOKEN
        } else {
            runCatching { file.readText().trim().takeIf(String::isNotEmpty) }.getOrNull()
        }
        cachedRoot = canonicalRoot
        cachedToken = token
        return token
    }

    /** Must complete before the corresponding durable authorization mutation starts. */
    @Synchronized
    fun invalidateBeforeMutation(filesRoot: File) {
        val canonicalRoot = filesRoot.canonicalPath
        check(filesRoot.isDirectory || filesRoot.mkdirs()) {
            "Unable to create daemon authorization root"
        }
        val token = UUID.randomUUID().toString()
        FileOutputStream(File(filesRoot, FILE_NAME), false).use { output ->
            output.write(token.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        cachedRoot = canonicalRoot
        cachedToken = token
    }
}
