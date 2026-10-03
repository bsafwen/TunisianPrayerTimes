package com.tunisianprayertimes.quran.assets

import android.content.Context
import android.media.MediaPlayer
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Where one Quran file can be read on this device. */
sealed interface QuranAssetSource {
    /** Inside the app itself, e.g. a build that bundles every file. */
    data class Bundled(val assetPath: String) : QuranAssetSource

    /** In a downloaded pack. */
    data class Downloaded(val file: File) : QuranAssetSource
}

internal fun QuranAssetSource.open(context: Context): InputStream = when (this) {
    is QuranAssetSource.Bundled -> context.assets.open(assetPath)
    is QuranAssetSource.Downloaded -> FileInputStream(file)
}

internal fun QuranAssetSource.attachTo(player: MediaPlayer, context: Context) {
    when (this) {
        // openFd uses a long APK offset and length, including assets beyond the 2 GB mark.
        is QuranAssetSource.Bundled -> context.assets.openFd(assetPath).use { file ->
            player.setDataSource(file.fileDescriptor, file.startOffset, file.length)
        }
        // Never a path: the media server may open paths itself, without this app's file access.
        // MediaPlayer keeps its own duplicate of the descriptor.
        is QuranAssetSource.Downloaded -> FileInputStream(file).use { stream -> player.setDataSource(stream.fd) }
    }
}
