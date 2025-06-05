package com.example.simplemediaplayer

import android.app.*
import android.content.ContentUris
import android.content.Intent
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat

class MusicService : Service() {

    private val binder = MusicBinder()
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var songList: MutableList<Song>
    private var currentSongIndex = 0
    private var isPlaying = false

    private var onSongChangeListener: ((Song) -> Unit)? = null
    private var onPlaybackStateChangeListener: ((Boolean) -> Unit)? = null
    private var onProgressUpdateListener: ((Int, Int) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    val currentPosition = player.currentPosition
                    val duration = player.duration
                    onProgressUpdateListener?.invoke(currentPosition, duration)
                    handler.postDelayed(this, 1000)
                }
            }
        }
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "MusicPlayerChannel"
        const val ACTION_PLAY_PAUSE = "ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "ACTION_NEXT"
        const val ACTION_PREVIOUS = "ACTION_PREVIOUS"
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onCreate() {
        super.onCreate()
        songList = mutableListOf()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleNotificationAction(intent)
        return START_STICKY
    }

    private fun handleNotificationAction(intent: Intent?) {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> playNext()
            ACTION_PREVIOUS -> playPrevious()
        }
    }

    fun setSongList(songs: MutableList<Song>) {
        this.songList = songs
    }

    fun playSong(index: Int) {
        if (index < 0 || index >= songList.size) {
            Log.e("MusicService", "Invalid song index: $index, songList size: ${songList.size}")
            return
        }

        currentSongIndex = index
        val song = songList[currentSongIndex]

        Log.d("MusicService", "Attempting to play: ${song.title} with ID: ${song.id}")

        val uri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            song.id
        )

        Log.d("MusicService", "Generated URI: $uri")

        try {
            // Release previous player
            mediaPlayer?.release()
            mediaPlayer = null

            // Create new MediaPlayer
            mediaPlayer = MediaPlayer.create(applicationContext, uri)

            if (mediaPlayer == null) {
                Log.e("MusicService", "MediaPlayer creation failed for URI: $uri")
                Log.e("MusicService", "Song details - Title: ${song.title}, Artist: ${song.artist}, Path: ${song.data}")

                // Thử tạo MediaPlayer bằng cách khác
                tryAlternativePlayback(song)
                return
            }

            mediaPlayer?.apply {
                setOnPreparedListener {
                    Log.d("MusicService", "MediaPlayer prepared, starting playback")
                    start()
                    this@MusicService.isPlaying = true
                    onSongChangeListener?.invoke(song)
                    onPlaybackStateChangeListener?.invoke(true)
                    startProgressUpdate()
                    showNotification()
                }

                setOnCompletionListener {
                    Log.d("MusicService", "Song completed, playing next")
                    playNext()
                }

                setOnErrorListener { mp, what, extra ->
                    Log.e("MusicService", "MediaPlayer error: what=$what, extra=$extra")
                    false
                }

                // Prepare async để tránh block UI thread
                prepareAsync()
            }

        } catch (e: Exception) {
            Log.e("MusicService", "Exception in playSong: ${e.message}", e)
            tryAlternativePlayback(song)
        }
    }

    private fun tryAlternativePlayback(song: Song) {
        try {
            Log.d("MusicService", "Trying alternative playback with file path")

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(song.data) // Sử dụng đường dẫn file trực tiếp
                setOnPreparedListener {
                    Log.d("MusicService", "Alternative MediaPlayer prepared")
                    start()
                    this@MusicService.isPlaying = true
                    onSongChangeListener?.invoke(song)
                    onPlaybackStateChangeListener?.invoke(true)
                    startProgressUpdate()
                    showNotification()
                }

                setOnErrorListener { mp, what, extra ->
                    Log.e("MusicService", "Alternative MediaPlayer error: what=$what, extra=$extra")
                    false
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("MusicService", "Alternative playback also failed: ${e.message}", e)
            // Thông báo cho UI về lỗi
            onPlaybackStateChangeListener?.invoke(false)
        }
    }


    fun togglePlayPause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                isPlaying = false
                handler.removeCallbacks(updateProgressRunnable)
            } else {
                player.start()
                isPlaying = true
                startProgressUpdate()
            }
            onPlaybackStateChangeListener?.invoke(isPlaying)
            showNotification()
        }
    }

    fun playNext() {
        if (songList.isEmpty()) return
        currentSongIndex = (currentSongIndex + 1) % songList.size
        playSong(currentSongIndex)
    }

    fun playPrevious() {
        if (songList.isEmpty()) return
        currentSongIndex = if (currentSongIndex - 1 < 0) songList.size - 1 else currentSongIndex - 1
        playSong(currentSongIndex)
    }

    fun seekTo(position: Int) {
        mediaPlayer?.seekTo(position)
    }

    fun setOnSongChangeListener(listener: (Song) -> Unit) {
        onSongChangeListener = listener
    }

    fun setOnPlaybackStateChangeListener(listener: (Boolean) -> Unit) {
        onPlaybackStateChangeListener = listener
    }

    fun setOnProgressUpdateListener(listener: (Int, Int) -> Unit) {
        onProgressUpdateListener = listener
    }

    private fun startProgressUpdate() {
        handler.removeCallbacks(updateProgressRunnable)
        handler.post(updateProgressRunnable)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Player",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music Player Controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification() {
        if (songList.isEmpty() || currentSongIndex >= songList.size) return

        val song = songList[currentSongIndex]

        val playPauseIntent = Intent(this, MusicService::class.java).apply {
            action = ACTION_PLAY_PAUSE
        }
        val playPausePendingIntent = PendingIntent.getService(
            this, 0, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = Intent(this, MusicService::class.java).apply {
            action = ACTION_NEXT
        }
        val nextPendingIntent = PendingIntent.getService(
            this, 1, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val previousIntent = Intent(this, MusicService::class.java).apply {
            action = ACTION_PREVIOUS
        }
        val previousPendingIntent = PendingIntent.getService(
            this, 2, previousIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 3, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                previousPendingIntent
            )
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                playPausePendingIntent
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                nextPendingIntent
            )
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        handler.removeCallbacks(updateProgressRunnable)
        stopForeground(true)
    }
}