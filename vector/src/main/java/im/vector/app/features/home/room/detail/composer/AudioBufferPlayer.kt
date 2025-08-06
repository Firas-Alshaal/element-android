/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

/*
package im.vector.app.features.home.room.detail.composer

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.io.File
import java.util.LinkedList
import java.util.Queue

class AudioBufferPlayer {
    private val audioQueue: Queue<ByteArray> = LinkedList()
    private var isPlaying = false

    fun appendChunk(chunk: ByteArray) {
        audioQueue.offer(chunk)
        if (!isPlaying) {
            playNext()
        }
    }

    private fun playNext() {
        val chunk = audioQueue.poll() ?: return
        isPlaying = true
        val tempFile = File.createTempFile("ptt_chunk", ".pcm")
        tempFile.writeBytes(chunk)

        val player = AudioTrack(
                AudioManager.STREAM_MUSIC,
                16000, // sample rate
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                chunk.size,
                AudioTrack.MODE_STATIC
        )

        player.write(chunk, 0, chunk.size)
        player.play()
        player.setNotificationMarkerPosition(chunk.size / 2)
        player.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack?) {
                isPlaying = false
                playNext()
            }

            override fun onPeriodicNotification(track: AudioTrack?) {}
        })
    }
}
*/
