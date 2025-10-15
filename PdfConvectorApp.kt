package com.example.pdfconvector

import android.app.Application

class PdfConvectorApp : Application() {
    val sharedViewModel: SharedViewModel by lazy { SharedViewModel() }
}