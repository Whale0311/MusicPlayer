package com.example.simplemediaplayer

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val data: String // Đổi tên từ 'path' thành 'data' để match với MusicService
) {
    fun getFormattedDuration(): String {
        val seconds = (duration / 1000) % 60
        val minutes = (duration / (1000 * 60)) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    // Thêm method để debug
    fun isFileValid(): Boolean {
        return java.io.File(data).exists()
    }

    override fun toString(): String {
        return "Song(id=$id, title='$title', artist='$artist', duration=$duration, data='$data', fileExists=${isFileValid()})"
    }
}