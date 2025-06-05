package com.example.simplemediaplayer

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

class SongAdapter(private val context: Context, private val songList: List<Song>) : BaseAdapter() {

    override fun getCount(): Int = songList.size

    override fun getItem(position: Int): Any = songList[position]

    override fun getItemId(position: Int): Long = songList[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_song, parent, false)

        val song = songList[position]

        val titleTextView = view.findViewById<TextView>(R.id.songTitleItem)
        val artistTextView = view.findViewById<TextView>(R.id.songArtistItem)
        val durationTextView = view.findViewById<TextView>(R.id.songDurationItem)

        titleTextView.text = song.title
        artistTextView.text = song.artist
        durationTextView.text = song.getFormattedDuration()

        return view
    }
}