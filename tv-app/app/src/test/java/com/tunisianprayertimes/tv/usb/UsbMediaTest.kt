package com.tunisianprayertimes.tv.usb

import com.tunisianprayertimes.tv.data.LocalMediaManager
import com.tunisianprayertimes.tv.data.MediaKind
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbMediaTest {

    private val root: File = Files.createTempDirectory("media").toFile()
    private val key = RemovableVolume(File(root, "key/Android/data/com.tunisianprayertimes.tv/files"))
    private val store = LocalMediaManager(File(root, "tv/media"))
    private var handled = ""
    private val inbox = UsbMediaInbox(store, { handled }, { handled = it })

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 0x4A, 0x46, 0x49, 0x46, 0, 1)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)

    private fun put(kind: MediaKind, name: String, bytes: ByteArray) =
        File(key.appFolder, "${kind.folder}/$name").apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    @Test
    fun onlyRealImagesAreTaken() {
        put(MediaKind.BACKGROUNDS, "a.jpg", jpeg)
        put(MediaKind.BACKGROUNDS, "b.png", png)
        put(MediaKind.BACKGROUNDS, "fake.jpg", "not an image".toByteArray())
        put(MediaKind.BACKGROUNDS, "notes.txt", "hello".toByteArray())
        val found = UsbMedia.find(listOf(key))!!
        assertEquals(listOf("a.jpg", "b.png"), found.images.getValue(MediaKind.BACKGROUNDS).map { it.name })
        assertTrue(found.images.getValue(MediaKind.ANNOUNCEMENTS).isEmpty())
    }

    @Test
    fun emptyFoldersOfferNothing() {
        UsbMedia.ensureFolders(key)
        assertTrue(File(key.appFolder, "backgrounds").isDirectory)
        assertNull(UsbMedia.find(listOf(key)))
    }

    @Test
    fun imagesAreCopiedOnceAndReplaceOnlyTheirKind() {
        store.replace(MediaKind.ANNOUNCEMENTS, listOf(put(MediaKind.ANNOUNCEMENTS, "old.png", png)))
        File(key.appFolder, "announcements").deleteRecursively()
        put(MediaKind.BACKGROUNDS, "new.jpg", jpeg)

        val found = inbox.scan(listOf(key))!!
        assertTrue(inbox.apply(found))
        assertEquals(listOf("new.jpg"), store.images(MediaKind.BACKGROUNDS).map { it.name })
        assertEquals("announcements the key did not have are kept", listOf("old.png"), store.images(MediaKind.ANNOUNCEMENTS).map { it.name })
        assertNull("the same images are not offered again", inbox.scan(listOf(key)))

        // Taking the key out afterwards changes nothing on the TV.
        File(root, "key").deleteRecursively()
        assertEquals(listOf("new.jpg"), store.images(MediaKind.BACKGROUNDS).map { it.name })
    }

    @Test
    fun aDismissedSetIsOfferedAgainOnlyWhenItChanges() {
        put(MediaKind.BACKGROUNDS, "a.jpg", jpeg)
        inbox.dismiss(inbox.scan(listOf(key))!!)
        assertNull(inbox.scan(listOf(key)))
        put(MediaKind.BACKGROUNDS, "b.png", png)
        assertTrue(inbox.scan(listOf(key)) != null)
        store.clear(MediaKind.BACKGROUNDS)
        assertFalse(File(root, "tv/media/backgrounds").exists())
    }
}
