package org.apptwin.archive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceArchiveCodecTest {
    @Test
    fun `round trip streams multiple source trees with verified index`() {
        val workspace = Files.createTempDirectory("space-archive-roundtrip").toFile()
        val user = workspace.resolve("user").apply { mkdirs() }
        user.resolve("prefs/settings.json").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("{\"signedIn\":true}")
        }
        val directBoot = workspace.resolve("user-de").apply { mkdirs() }
        directBoot.resolve("state.bin").writeBytes(byteArrayOf(0, 1, 2, 3))
        val encoded = ByteArrayOutputStream()

        val written = SpaceArchiveWriter.write(
            encoded,
            manifest(),
            listOf(
                SpaceArchiveSource("user", user),
                SpaceArchiveSource("user_de", directBoot),
            ),
        )

        val staging = workspace.resolve("staging")
        val restored = SpaceArchiveReader().readAndExtract(
            ByteArrayInputStream(encoded.toByteArray()),
            staging,
        )
        assertEquals(manifest(), restored.manifest)
        assertEquals(written.files, restored.files)
        assertEquals(
            "{\"signedIn\":true}",
            staging.resolve("payload/user/prefs/settings.json").readText(),
        )
        assertTrue(
            staging.resolve("payload/user_de/state.bin").readBytes()
                .contentEquals(byteArrayOf(0, 1, 2, 3)),
        )
        assertEquals(64, written.files.single { it.path.endsWith("settings.json") }.sha256.length)
    }

    @Test
    fun `tampered payload is rejected before staging is created`() {
        val fixture = validArchive("original".toByteArray())
        val tampered = rewriteZip(fixture) { name, bytes ->
            if (name.startsWith("payload/")) "tampered".toByteArray() else bytes
        }
        val staging = Files.createTempDirectory("space-archive-tamper").toFile().resolve("staging")

        assertThrows(SpaceArchiveException::class.java) {
            SpaceArchiveReader().readAndExtract(ByteArrayInputStream(tampered), staging)
        }
        assertFalse(staging.exists())
    }

    @Test
    fun `manifest rejects unsupported schema invalid UUID package and state`() {
        val mutations = listOf<(String) -> String>(
            { it.replace("\"schemaVersion\":1", "\"schemaVersion\":2") },
            { it.replace(ARCHIVE_ID, "not-a-uuid") },
            { it.replace("jp.naver.line.android", "invalid-package") },
            { it.replace("\"state\":\"ENABLED\"", "\"state\":\"UNKNOWN\"") },
        )
        mutations.forEachIndexed { index, mutate ->
            val invalid = rewriteZip(validArchive(byteArrayOf(1))) { name, bytes ->
                if (name == "manifest.json") {
                    mutate(bytes.toString(StandardCharsets.UTF_8)).toByteArray()
                } else {
                    bytes
                }
            }
            val staging = Files.createTempDirectory("space-archive-manifest-$index").toFile()
                .resolve("staging")
            assertThrows(SpaceArchiveException::class.java) {
                SpaceArchiveReader().readAndExtract(ByteArrayInputStream(invalid), staging)
            }
            assertFalse(staging.exists())
        }
    }

    @Test
    fun `zip slip absolute and backslash paths are rejected`() {
        val unsafePaths = listOf(
            "payload/user/../../outside",
            "/payload/user/file",
            "C:/payload/user/file",
            "payload\\user\\file",
        )
        unsafePaths.forEachIndexed { index, path ->
            val archive = zipOf(path to byteArrayOf(1))
            val parent = Files.createTempDirectory("space-archive-path-$index").toFile()
            val staging = parent.resolve("staging")
            assertThrows(SpaceArchiveException::class.java) {
                SpaceArchiveReader().readAndExtract(ByteArrayInputStream(archive), staging)
            }
            assertFalse(staging.exists())
            assertFalse(parent.resolve("outside").exists())
        }
    }

    @Test
    fun `duplicate physical zip entry is rejected`() {
        val duplicate = rawStoredZip(
            "manifest.json" to "one".toByteArray(),
            "manifest.json" to "two".toByteArray(),
        )
        val staging = Files.createTempDirectory("space-archive-duplicate").toFile().resolve("staging")

        val failure = assertThrows(SpaceArchiveException::class.java) {
            SpaceArchiveReader().readAndExtract(ByteArrayInputStream(duplicate), staging)
        }
        assertTrue(failure.message.orEmpty().contains("Duplicate archive entry"))
        assertFalse(staging.exists())
    }

    @Test
    fun `single file total and entry count limits fail closed`() {
        val archive = validArchive(ByteArray(8) { it.toByte() })
        val parent = Files.createTempDirectory("space-archive-limits").toFile()
        val limitCases = listOf(
            SpaceArchiveLimits(maxSingleFileBytes = 7),
            SpaceArchiveLimits(maxTotalUncompressedBytes = 16),
            SpaceArchiveLimits(maxEntryCount = 2),
        )

        limitCases.forEachIndexed { index, limits ->
            val staging = parent.resolve("staging-$index")
            assertThrows(SpaceArchiveException::class.java) {
                SpaceArchiveReader(limits).readAndExtract(ByteArrayInputStream(archive), staging)
            }
            assertFalse(staging.exists())
        }
    }

    @Test
    fun `writer skips generated symlink while preserving regular files`() {
        val root = Files.createTempDirectory("space-archive-symlink")
        val target = Files.createTempFile("space-archive-target", ".txt")
        Files.createSymbolicLink(root.resolve("link"), target)
        root.resolve("state.txt").toFile().writeText("logged-in")

        val archive = ByteArrayOutputStream().also { output ->
            SpaceArchiveWriter.write(
                output,
                manifest(),
                listOf(SpaceArchiveSource("user", root.toFile())),
            )
        }.toByteArray()
        val staging = Files.createTempDirectory("space-archive-symlink-parent")
            .resolve("staging")
            .toFile()

        val restored = SpaceArchiveReader().readAndExtract(ByteArrayInputStream(archive), staging)

        assertEquals("logged-in", staging.resolve("payload/user/state.txt").readText())
        assertFalse(staging.resolve("payload/user/link").exists())
        assertEquals(listOf("payload/user/state.txt"), restored.files.map(SpaceArchiveFile::path))
    }

    private fun validArchive(payload: ByteArray): ByteArray {
        val workspace = Files.createTempDirectory("space-archive-fixture").toFile()
        val root = workspace.resolve("user").apply { mkdirs() }
        root.resolve("data.bin").writeBytes(payload)
        return ByteArrayOutputStream().also { output ->
            SpaceArchiveWriter.write(
                output,
                manifest(),
                listOf(SpaceArchiveSource("user", root)),
            )
        }.toByteArray()
    }

    private fun manifest() = SpaceArchiveManifest(
        archiveId = ARCHIVE_ID,
        sourceSpaceId = SOURCE_SPACE_ID,
        name = "LINE test",
        createdAt = 1_750_000_000_000,
        apps = listOf(
            SpaceArchiveApp(
                packageName = "jp.naver.line.android",
                addedAt = 1_740_000_000_000,
                state = SpaceArchiveAppState.ENABLED,
                revisionId = "26.13.0-123-deadbeef",
            ),
        ),
        gmsEnabled = true,
        custodianKeyspaceId = KEYSPACE_ID,
    )

    private fun rewriteZip(
        archive: ByteArray,
        transform: (String, ByteArray) -> ByteArray,
    ): ByteArray {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(archive)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries += entry.name to transform(entry.name, input.readBytes())
                input.closeEntry()
            }
        }
        return zipOf(*entries.toTypedArray())
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    /** Local-file records are sufficient for ZipInputStream and allow duplicate-name fixtures. */
    private fun rawStoredZip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            entries.forEach { (name, content) ->
                val encodedName = name.toByteArray(StandardCharsets.UTF_8)
                val crc = CRC32().apply { update(content) }.value
                output.writeLittleEndianInt(0x04034b50)
                output.writeLittleEndianShort(20)
                output.writeLittleEndianShort(0)
                output.writeLittleEndianShort(0)
                output.writeLittleEndianShort(0)
                output.writeLittleEndianShort(0)
                output.writeLittleEndianInt(crc)
                output.writeLittleEndianInt(content.size.toLong())
                output.writeLittleEndianInt(content.size.toLong())
                output.writeLittleEndianShort(encodedName.size)
                output.writeLittleEndianShort(0)
                output.write(encodedName)
                output.write(content)
            }
        }.toByteArray()

    private fun ByteArrayOutputStream.writeLittleEndianShort(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun ByteArrayOutputStream.writeLittleEndianInt(value: Long) {
        repeat(4) { shift -> write((value ushr (shift * 8) and 0xff).toInt()) }
    }

    private companion object {
        const val ARCHIVE_ID = "00000000-0000-0000-0000-000000000001"
        const val SOURCE_SPACE_ID = "00000000-0000-0000-0000-000000000002"
        const val KEYSPACE_ID = "00000000-0000-0000-0000-000000000003"
    }
}
