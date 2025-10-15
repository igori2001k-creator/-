package com.example.pdfconvector

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import android.net.Uri

class SharedViewModel : ViewModel() {
    private val _extractionProgress = MutableLiveData<Int>(0)
    val extractionProgress: LiveData<Int> = _extractionProgress

    private val _isExtractionRunning = MutableLiveData<Boolean>(false)
    val isExtractionRunning: LiveData<Boolean> = _isExtractionRunning

    private val _extractedImages = MutableLiveData<MutableList<Uri>>(mutableListOf())
    val extractedImages: LiveData<MutableList<Uri>> = _extractedImages

    private val _extractionMessage = MutableLiveData<String>("")
    val extractionMessage: LiveData<String> = _extractionMessage

    fun updateProgress(progress: Int) {
        _extractionProgress.postValue(progress)
    }

    fun setExtractionRunning(running: Boolean) {
        _isExtractionRunning.postValue(running)
    }

    fun addExtractedImages(images: List<Uri>) {
        val currentList = _extractedImages.value ?: mutableListOf()
        currentList.addAll(images)
        _extractedImages.postValue(currentList)
    }

    fun clearExtractedImages() {
        _extractedImages.postValue(mutableListOf())
    }

    fun setExtractionMessage(message: String) {
        _extractionMessage.postValue(message)
    }
}