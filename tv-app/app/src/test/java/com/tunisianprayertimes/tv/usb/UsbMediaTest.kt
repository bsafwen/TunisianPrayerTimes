package com.tunisianprayertimes.tv.usb

import com.tunisianprayertimes.tv.data.LocalMediaManager
import com.tunisianprayertimes.tv.data.MediaKind
import com.tunisianprayertimes.tv.data.ScreenImage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun aResetLeavesNoImageNorTextFile() {
        store.add(MediaKind.BACKGROUNDS, "a.jpg", jpeg)
        store.add(MediaKind.ANNOUNCEMENTS, "b.png", png)
        store.add(MediaKind.ANNOUNCEMENTS, "notice.txt", "تبرّعوا للمسجد".toByteArray())
        File(root, "tv/media/announcements.new").mkdirs() // a copy cut short
        store.clearAll()
        MediaKind.entries.forEach { assertTrue(store.images(it).isEmpty()) }
        assertTrue(store.textFiles().isEmpty())
        assertFalse(File(root, "tv/media").exists())
        // Images brought afterwards are stored as on a new TV.
        store.add(MediaKind.BACKGROUNDS, "c.jpg", jpeg)
        assertEquals(1, store.images(MediaKind.BACKGROUNDS).size)
    }

    @Test
    fun onlyRealImagesAreTakenAndTheOthersAreReported() {
        put(MediaKind.BACKGROUNDS, "a.jpg", jpeg)
        put(MediaKind.BACKGROUNDS, "b.png", png)
        put(MediaKind.BACKGROUNDS, "fake.jpg", "not an image".toByteArray())
        put(MediaKind.BACKGROUNDS, "notes.txt", "hello".toByteArray())
        put(MediaKind.BACKGROUNDS, "IMG_1.HEIC", byteArrayOf(0, 0, 0, 0x18))
        put(MediaKind.BACKGROUNDS, "Thumbs.db", byteArrayOf(1, 2, 3))
        put(MediaKind.BACKGROUNDS, "._a.jpg", jpeg)
        val found = UsbMedia.find(key)!!
        assertEquals(listOf("a.jpg", "b.png"), found.images.getValue(MediaKind.BACKGROUNDS).map { it.name })
        assertTrue(found.images.getValue(MediaKind.ANNOUNCEMENTS).isEmpty())
        // Not a list of every file on the key: what the admin meant as media, and why it is left out.
        assertEquals(
            setOf("fake.jpg" to RejectedFile.Reason.FORMAT, "notes.txt" to RejectedFile.Reason.MISPLACED, "IMG_1.HEIC" to RejectedFile.Reason.FORMAT),
            found.rejected.map { it.file.name to it.reason }.toSet(),
        )
    }

    @Test
    fun tooLargeTooManyAndMisplacedFilesAreReported() {
        java.io.RandomAccessFile(put(MediaKind.BACKGROUNDS, "big.jpg", jpeg), "rw").use { it.setLength(UsbMedia.MAX_BYTES + 1) }
        repeat(UsbMedia.MAX_FILES + 2) { put(MediaKind.ANNOUNCEMENTS, "p%02d.png".format(it), png) }
        File(key.appFolder, "photo.jpg").writeBytes(jpeg) // next to mosque-tv.json
        val found = UsbMedia.find(key)!!
        assertEquals(UsbMedia.MAX_FILES, found.images.getValue(MediaKind.ANNOUNCEMENTS).size)
        assertEquals(
            mapOf(RejectedFile.Reason.TOO_LARGE to 1, RejectedFile.Reason.TOO_MANY to 2, RejectedFile.Reason.MISPLACED to 1),
            found.rejected.groupingBy { it.reason }.eachCount(),
        )
    }

    @Test
    fun aKeyWithNothingUsableIsStillReportedOnce() {
        put(MediaKind.BACKGROUNDS, "IMG_1.heic", byteArrayOf(0, 0, 0, 0x18))
        val found = inbox.scan(listOf(key))!!
        assertTrue(found.isEmpty)
        assertEquals(RejectedFile.Reason.FORMAT, found.rejected.single().reason)
        inbox.dismiss(found)
        assertNull(inbox.scan(listOf(key)))
    }

    @Test
    fun emptyFoldersOfferNothing() {
        UsbMedia.ensureFolders(key)
        assertTrue(File(key.appFolder, "backgrounds").isDirectory)
        assertNull(UsbMedia.find(key))
    }

    @Test
    fun aReadOnlyKeyGetsNoFolders() {
        val readOnly = RemovableVolume(File(root, "ntfs/Android/data/com.tunisianprayertimes.tv/files"), readOnly = true)
        UsbMedia.ensureFolders(readOnly)
        assertFalse(readOnly.appFolder.exists())
    }

    @Test
    fun imagesAreCopiedOnceAndReplaceOnlyTheirKind() {
        store.replace(MediaKind.ANNOUNCEMENTS, listOf(put(MediaKind.ANNOUNCEMENTS, "old.png", png)))
        File(key.appFolder, "announcements").deleteRecursively()
        put(MediaKind.BACKGROUNDS, "new.jpg", jpeg)

        val found = inbox.scan(listOf(key))!!
        assertEquals(UsbMediaCopy.Done(1), inbox.apply(found))
        assertEquals(listOf("new.jpg"), store.images(MediaKind.BACKGROUNDS).map { it.name })
        assertEquals("announcements the key did not have are kept", listOf("old.png"), store.images(MediaKind.ANNOUNCEMENTS).map { it.name })
        assertNull("the same images are not offered again", inbox.scan(listOf(key)))

        // Taking the key out afterwards changes nothing on the TV.
        File(root, "key").deleteRecursively()
        assertEquals(listOf("new.jpg"), store.images(MediaKind.BACKGROUNDS).map { it.name })
    }

    @Test
    fun anOfferKnowsWhenItsKeyIsTakenOut() {
        put(MediaKind.BACKGROUNDS, "a.jpg", jpeg)
        val found = inbox.scan(listOf(key))!!
        assertTrue(UsbMedia.stillThere(found))
        File(root, "key").deleteRecursively()
        assertFalse(UsbMedia.stillThere(found))
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

    @Test
    fun eachKeyIsRememberedOnItsOwn() {
        val other = RemovableVolume(File(root, "other/Android/data/com.tunisianprayertimes.tv/files"))
        put(MediaKind.BACKGROUNDS, "a.jpg", jpeg)
        File(other.appFolder, "backgrounds").mkdirs()
        File(other.appFolder, "backgrounds/b.jpg").writeBytes(jpeg)
        inbox.dismiss(inbox.scan(listOf(key))!!)
        inbox.dismiss(inbox.scan(listOf(other))!!)
        // The first key comes back after the second: still answered.
        assertNull(inbox.scan(listOf(key)))
        // With both in, one answered key never hides the other.
        File(other.appFolder, "backgrounds/c.jpg").writeBytes(jpeg)
        assertEquals(other.appFolder, inbox.scan(listOf(key, other))!!.images.getValue(MediaKind.BACKGROUNDS).first().parentFile!!.parentFile)
    }

    @Test
    fun aKeyWithOnlyATextFileKeepsTheTvsImages() {
        // Six photos sent from the phone, and a .txt announcement from an earlier key.
        repeat(6) { store.add(MediaKind.ANNOUNCEMENTS, "phone$it.jpg", jpeg) }
        store.replace(MediaKind.ANNOUNCEMENTS, listOf(put(MediaKind.ANNOUNCEMENTS, "old.txt", "درس قديم".toByteArray())))
        File(key.appFolder, "announcements").deleteRecursively()
        put(MediaKind.ANNOUNCEMENTS, "lesson.txt", "درس بعد العشاء".toByteArray())

        val found = inbox.scan(listOf(key))!!
        assertEquals("the offer says what the copy takes away", mapOf(MediaKind.ANNOUNCEMENTS to 1), found.replaced)
        assertEquals(UsbMediaCopy.Done(1), inbox.apply(found))
        assertEquals(6, store.images(MediaKind.ANNOUNCEMENTS).size)
        assertEquals(listOf("درس بعد العشاء"), store.textFileAnnouncements())

        // Images from a key replace the images, and keep the texts.
        File(key.appFolder, "announcements/lesson.txt").delete()
        put(MediaKind.ANNOUNCEMENTS, "poster.png", png)
        val images = inbox.scan(listOf(key))!!
        assertEquals(mapOf(MediaKind.ANNOUNCEMENTS to 6), images.replaced)
        inbox.apply(images)
        assertEquals(listOf("poster.png"), store.images(MediaKind.ANNOUNCEMENTS).map { it.name })
        assertEquals(listOf("درس بعد العشاء"), store.textFileAnnouncements())
    }

    @Test
    fun aCopyThatFailsIsOfferedAgainAndTheOtherKindIsStillCopied() {
        store.replace(MediaKind.BACKGROUNDS, listOf(put(MediaKind.BACKGROUNDS, "old.jpg", jpeg)))
        File(key.appFolder, "backgrounds").deleteRecursively()
        val pulled = put(MediaKind.BACKGROUNDS, "a.jpg", jpeg)
        val modified = pulled.lastModified()
        put(MediaKind.ANNOUNCEMENTS, "poster.png", png)
        val found = inbox.scan(listOf(key))!!

        // The key goes before the backgrounds are read (a pulled key, a bad sector).
        pulled.delete()
        assertEquals(UsbMediaCopy.Failed, inbox.apply(found))
        assertEquals("the previous backgrounds stay", listOf("old.jpg"), store.images(MediaKind.BACKGROUNDS).map { it.name })
        assertEquals("the announcements were copied all the same", listOf("poster.png"), store.images(MediaKind.ANNOUNCEMENTS).map { it.name })
        assertFalse("nothing is left half copied", File(root, "tv/media/backgrounds.new").exists())

        // The same key, back in: offered again.
        pulled.writeBytes(jpeg)
        pulled.setLastModified(modified)
        assertEquals(found.signature, inbox.scan(listOf(key))?.signature)
    }

    @Test
    fun aKeyPulledOutBeforeThePhotosAreReadIsAFailedCopyNotBrokenImages() {
        // The TV's own store: the decoder would take the missing files for broken images.
        val tvStore = LocalMediaManager(File(root, "tv/media"), ScreenImage::store)
        val tvInbox = UsbMediaInbox(tvStore, { handled }, { handled = it })
        tvStore.add(MediaKind.BACKGROUNDS, "old.jpg", jpeg)
        val photos = (1..3).map { put(MediaKind.BACKGROUNDS, "photo$it.jpg", jpeg) }
        val modified = photos.map { it.lastModified() }
        val found = tvInbox.scan(listOf(key))!!

        photos.forEach { it.delete() }
        // A read error, before any decoding: never taken for a broken image.
        assertThrows(IOException::class.java) { ScreenImage.store(photos.first(), File(root, "copy.jpg")) }
        assertEquals(UsbMediaCopy.Failed, tvInbox.apply(found))
        assertEquals("the previous backgrounds stay", listOf("old.jpg"), tvStore.images(MediaKind.BACKGROUNDS).map { it.name })

        // The same key, back in: offered again.
        photos.zip(modified).forEach { (photo, time) -> photo.writeBytes(jpeg); photo.setLastModified(time) }
        assertEquals(found.signature, tvInbox.scan(listOf(key))?.signature)
    }

    @Test
    fun eachFileNeedsRoomForItselfNotTheWholeSetAtItsSizeOnTheKey() {
        // A small box, with room for one photo from the key at a time: each is shrunk once copied.
        val tv = File(root, "tv/media")
        val disk = LocalMediaManager.RESERVE_BYTES + 1_500
        val small = LocalMediaManager(
            tv,
            storeImage = { source, target -> source.copyTo(target); target.writeBytes(jpeg); true },
            freeSpace = { disk - tv.walk().filter { it.isFile }.sumOf { it.length() } },
        )
        val smallInbox = UsbMediaInbox(small, { handled }, { handled = it })
        repeat(3) { put(MediaKind.BACKGROUNDS, "photo$it.jpg", jpeg + ByteArray(1_000)) }
        assertEquals(UsbMediaCopy.Done(3), smallInbox.apply(smallInbox.scan(listOf(key))!!))

        // A photo the room left cannot take even for a moment: nothing changes, and it is offered again.
        put(MediaKind.BACKGROUNDS, "huge.jpg", jpeg + ByteArray(2_000))
        val huge = smallInbox.scan(listOf(key))!!
        assertEquals(UsbMediaCopy.NoRoom, smallInbox.apply(huge))
        assertEquals(listOf("photo0.jpg", "photo1.jpg", "photo2.jpg"), small.images(MediaKind.BACKGROUNDS).map { it.name })
        assertFalse(File(tv, "backgrounds.new").exists())
        assertEquals(huge.signature, smallInbox.scan(listOf(key))?.signature)
    }

    @Test
    fun imagesTheTvCannotDecodeAreLeftOutAndCounted() {
        val picky = LocalMediaManager(File(root, "tv/media"), storeImage = { source, target ->
            !source.name.startsWith("broken") && run { source.copyTo(target); true }
        })
        val pickyInbox = UsbMediaInbox(picky, { handled }, { handled = it })
        picky.add(MediaKind.BACKGROUNDS, "phone.jpg", jpeg)
        put(MediaKind.BACKGROUNDS, "broken.jpg", jpeg)
        assertEquals(UsbMediaCopy.Done(0, unreadable = 1), pickyInbox.apply(pickyInbox.scan(listOf(key))!!))
        assertEquals("nothing readable came: the TV's image stays", listOf("phone.jpg"), picky.images(MediaKind.BACKGROUNDS).map { it.name })
        put(MediaKind.BACKGROUNDS, "good.jpg", jpeg)
        assertEquals(UsbMediaCopy.Done(1, unreadable = 1), pickyInbox.apply(pickyInbox.scan(listOf(key))!!))
        assertEquals(listOf("good.jpg"), picky.images(MediaKind.BACKGROUNDS).map { it.name })
    }

    @Test
    fun theHandledSetsAreTheLastSixteen() {
        var text = ""
        val signatures = HandledSignatures({ text }, { text = it })
        repeat(HandledSignatures.MAX + 1) { signatures.add("s$it") }
        assertFalse("the oldest goes", "s0" in signatures)
        assertTrue("s1" in signatures && "s${HandledSignatures.MAX}" in signatures)
        // Answered again, a set becomes the newest.
        signatures.add("s1")
        signatures.add("new")
        assertTrue("s1" in signatures)
        assertFalse("s2" in signatures)
    }
}
