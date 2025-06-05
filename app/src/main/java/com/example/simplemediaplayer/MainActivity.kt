package com.example.simplemediaplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var songListView: ListView
    private lateinit var playPauseBtn: ImageButton
    private lateinit var nextBtn: ImageButton
    private lateinit var prevBtn: ImageButton
    private lateinit var songTitle: TextView
    private lateinit var songArtist: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView

    private var musicService: MusicService? = null
    private var isServiceBound = false
    private var songList = mutableListOf<Song>()
    private lateinit var songAdapter: SongAdapter

    //Kết nối tới MusicService
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isServiceBound = true

            Log.d("MainActivity", "Service connected successfully")

            // *** QUAN TRỌNG: Chỉ set songList sau khi service đã connected ***
            if (songList.isNotEmpty()) {
                musicService?.setSongList(songList)
                Log.d("MainActivity", "Song list set to service: ${songList.size} songs")
            }

            musicService?.setOnSongChangeListener { song ->
                runOnUiThread {
                    updateUI(song)
                }
            }

            musicService?.setOnPlaybackStateChangeListener { isPlaying ->
                runOnUiThread {
                    val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    playPauseBtn.setImageResource(iconRes)
                }
            }


            musicService?.setOnProgressUpdateListener { progress, duration ->
                runOnUiThread {
                    seekBar.progress = progress
                    seekBar.max = duration
                    currentTimeText.text = formatTime(progress)
                    totalTimeText.text = formatTime(duration)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d("MainActivity", "Service disconnected")
            musicService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupSeekBar()
        checkPermissions()
    }

    private fun initViews() {
        songListView = findViewById(R.id.songListView)
        playPauseBtn = findViewById(R.id.playPauseBtn)
        nextBtn = findViewById(R.id.nextBtn)
        prevBtn = findViewById(R.id.prevBtn)
        songTitle = findViewById(R.id.songTitle)
        songArtist = findViewById(R.id.songArtist)
        seekBar = findViewById(R.id.seekBar)
        currentTimeText = findViewById(R.id.currentTime)
        totalTimeText = findViewById(R.id.totalTime)



        playPauseBtn.setOnClickListener {
            musicService?.togglePlayPause()
        }

        nextBtn.setOnClickListener {
            musicService?.playNext()
        }

        prevBtn.setOnClickListener {
            musicService?.playPrevious()
        }
    }
    private fun debugSongList() {
        Log.d("MainActivity", "=== DEBUG SONG LIST ===")
        Log.d("MainActivity", "Total songs: ${songList.size}")

        songList.forEachIndexed { index, song ->
            Log.d("MainActivity", "[$index] ID: ${song.id}, Title: ${song.title}")
            Log.d("MainActivity", "    Path: ${song.data}")
            Log.d("MainActivity", "    File exists: ${java.io.File(song.data).exists()}")
        }

        Log.d("MainActivity", "Service bound: $isServiceBound")
        Log.d("MainActivity", "MusicService null: ${musicService == null}")
        Log.d("MainActivity", "========================")
    }
    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    musicService?.seekTo(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        // Kiểm tra quyền dựa trên phiên bản Android
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ cần READ_MEDIA_AUDIO
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            // Android 12 và thấp hơn cần READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
        } else {
            loadSongs()
            startMusicService()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                loadSongs()
                startMusicService()
            } else {
                Toast.makeText(this, "Permissions required to access music files", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadSongs() {
        songList.clear()

        try {
            // Thử với URI khác nhau để tìm file MP3
            val uris = listOf(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Audio.Media.INTERNAL_CONTENT_URI
            )

            for (uri in uris) {
                querySongsFromUri(uri)
            }

            // Nếu vẫn không tìm thấy, hiển thị thông báo debug
            if (songList.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "No MP3 files found. Checking: ${songList.size} songs", Toast.LENGTH_LONG).show()
                }
                // Thử quét với selection rộng hơn
                queryAllAudioFiles()
            }

            setupAdapter()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error loading songs: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun querySongsFromUri(uri: Uri) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME
        )

        // Selection để lọc file MP3 và loại bỏ file hệ thống
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1 AND " +
                "${MediaStore.Audio.Media.DURATION} > 10000 AND " +
                "(${MediaStore.Audio.Media.DATA} LIKE '%.mp3' OR " +
                "${MediaStore.Audio.Media.DATA} LIKE '%.MP3')"

        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            val cursor: Cursor? = contentResolver.query(uri, projection, selection, null, sortOrder)

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val title = it.getString(titleColumn) ?: "Unknown Title"
                    val artist = it.getString(artistColumn) ?: "Unknown Artist"
                    val duration = it.getLong(durationColumn)
                    val path = it.getString(dataColumn)

                    // Kiểm tra file có tồn tại
                    if (path != null && java.io.File(path).exists()) {
                        songList.add(Song(id, title, artist, duration, path))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun queryAllAudioFiles() {
        // Thử query tất cả file audio không có điều kiện IS_MUSIC
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )

        val selection = "${MediaStore.Audio.Media.DURATION} > 5000"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            val cursor: Cursor? = contentResolver.query(uri, projection, selection, null, sortOrder)

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val title = it.getString(titleColumn) ?: "Unknown Title"
                    val artist = it.getString(artistColumn) ?: "Unknown Artist"
                    val duration = it.getLong(durationColumn)
                    val path = it.getString(dataColumn)

                    // Chỉ thêm file MP3
                    if (path != null &&
                        (path.lowercase().endsWith(".mp3")) &&
                        java.io.File(path).exists()) {
                        songList.add(Song(id, title, artist, duration, path))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupAdapter() {
        Log.d("MainActivity", "Setting up adapter with ${songList.size} songs")

        songAdapter = SongAdapter(this, songList)
        songListView.adapter = songAdapter

        songListView.setOnItemClickListener { _, _, position, _ ->
            Log.d("MainActivity", "Item clicked at position: $position")
            Log.d("MainActivity", "Song: ${songList[position].title}")
            Log.d("MainActivity", "Service bound: $isServiceBound")
            Log.d("MainActivity", "MusicService null: ${musicService == null}")

            if (musicService != null) {
                musicService?.playSong(position)
            } else {
                Log.e("MainActivity", "MusicService is null, cannot play song")
                Toast.makeText(this@MainActivity, "Music service not ready", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startMusicService() {
        val intent = Intent(this, MusicService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateUI(song: Song) {
        songTitle.text = song.title
        songArtist.text = song.artist
        totalTimeText.text = formatTime(song.duration.toInt())
    }

    private fun formatTime(milliseconds: Int): String {
        val seconds = (milliseconds / 1000) % 60
        val minutes = (milliseconds / (1000 * 60)) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onStart() {
        super.onStart()
        Log.d("MainActivity", "onStart called")

        if (!isServiceBound) {
            Log.d("MainActivity", "Binding to service...")
            val intent = Intent(this, MusicService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }


    }

    override fun onStop() {
        super.onStop()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}