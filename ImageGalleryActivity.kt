package com.example.pdfconvector

import android.net.Uri
import android.os.Bundle
import android.widget.GridView
import androidx.appcompat.app.AppCompatActivity

class ImageGalleryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        val imageUris = intent.getParcelableArrayListExtra<Uri>("imageUris") ?: return
        val gridView = findViewById<GridView>(R.id.gridView)

        gridView.adapter = ImageAdapter(this, imageUris)
    }
}