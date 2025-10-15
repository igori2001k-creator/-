package com.example.pdfconvector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.ViewModelProvider
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.ImageRenderInfo
import com.itextpdf.text.pdf.parser.PdfImageObject
import com.itextpdf.text.pdf.parser.PdfReaderContentParser
import com.itextpdf.text.pdf.parser.RenderListener
import com.itextpdf.text.pdf.parser.TextRenderInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class PdfExtractionService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var totalPages = 0
    private var processedPages = 0
    private lateinit var viewModel: SharedViewModel

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Получаем ViewModel через Application
        viewModel = (application as PdfConvectorApp).sharedViewModel
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val pdfUriString = it.getStringExtra("PDF_URI")
            val minSizeBytes = it.getIntExtra("MIN_SIZE_BYTES", 10 * 1024)

            pdfUriString?.let { uriString ->
                val pdfUri = Uri.parse(uriString)
                startForegroundServiceWork(pdfUri, minSizeBytes)
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceWork(pdfUri: Uri, minSizeBytes: Int) {
        val notification = createNotification("Подготовка к извлечению...", 0)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )

        serviceScope.launch {
            try {
                val extractedImages = mutableListOf<Uri>()
                val inputStream = contentResolver.openInputStream(pdfUri)
                val tempFile = File(cacheDir, "temp_${System.currentTimeMillis()}.pdf")

                inputStream?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val reader = PdfReader(tempFile.absolutePath)
                val parser = PdfReaderContentParser(reader)
                totalPages = reader.numberOfPages
                processedPages = 0

                // Уведомляем о начале процесса
                viewModel.setExtractionRunning(true)

                for (page in 1..totalPages) {
                    val imagesOnPage = mutableListOf<Uri>()
                    val imageListener = ServiceImageRenderListener(
                        this@PdfExtractionService,
                        imagesOnPage,
                        minSizeBytes
                    )
                    parser.processContent(page, imageListener)
                    extractedImages.addAll(imagesOnPage)

                    processedPages++
                    val progress = (processedPages * 100 / totalPages).toInt()

                    // Обновляем прогресс через ViewModel
                    viewModel.updateProgress(progress)
                    updateNotification("Обработано страниц: $processedPages/$totalPages", progress)

                    delay(50)
                }

                reader.close()
                tempFile.delete()

                // Сообщаем о завершении
                viewModel.addExtractedImages(extractedImages)
                viewModel.setExtractionMessage("Извлечено ${extractedImages.size} изображений в галерею")

                updateNotification("Извлечено ${extractedImages.size} изображений", 100)
                delay(2000)

            } catch (e: Exception) {
                Log.e("PdfExtractionService", "Ошибка извлечения", e)
                viewModel.setExtractionMessage("Ошибка: ${e.message}")
                updateNotification("Ошибка: ${e.message}", 0)
                delay(3000)
            } finally {
                viewModel.setExtractionRunning(false)
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Извлечение изображений из PDF",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление о процессе извлечения изображений"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String, progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PDF Convector")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progress, false)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(contentText: String, progress: Int) {
        val notification = createNotification(contentText, progress)
        val manager = getSystemService(NotificationManager::class.java) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val CHANNEL_ID = "pdf_extraction_channel"
        private const val NOTIFICATION_ID = 1
    }
}

// Внутренний класс для обработки изображений в сервисе
class ServiceImageRenderListener(
    private val context: Context,
    private val imageList: MutableList<Uri>,
    private val minSizeBytes: Int
) : RenderListener {

    override fun renderImage(renderInfo: ImageRenderInfo) {
        try {
            val imageObject = renderInfo.image
            if (imageObject != null) {
                extractImageFromXObject(imageObject)
            }
        } catch (e: Exception) {
            Log.e("ServicePDF", "Ошибка при обработке изображения", e)
        }
    }

    private fun extractImageFromXObject(xObject: PdfImageObject): Boolean {
        return try {
            val imageBytes: ByteArray? = xObject.imageAsBytes
            if (imageBytes != null && imageBytes.size >= minSizeBytes) {
                val inputStream: InputStream = ByteArrayInputStream(imageBytes)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (bitmap != null) {
                    val imageFormat = determineImageFormat(xObject)
                    val uri = saveImageToGallery(bitmap, imageFormat)
                    bitmap.recycle()

                    if (uri != null) {
                        imageList.add(uri)
                        Log.d("ServicePDF", "Сохранено изображение: $uri")
                        true
                    } else false
                } else false
            } else false
        } catch (e: Exception) {
            Log.e("ServicePDF", "Ошибка извлечения: ${e.message}")
            false
        }
    }

    private fun determineImageFormat(xObject: PdfImageObject): ImageFormat {
        return try {
            val fileType = xObject.fileType
            when {
                fileType.equals("jpg", true) || fileType.equals("jpeg", true) -> ImageFormat.JPEG
                else -> ImageFormat.PNG
            }
        } catch (e: Exception) {
            ImageFormat.PNG
        }
    }

    private fun saveImageToGallery(bitmap: Bitmap, imageFormat: ImageFormat): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveImageWithMediaStore(bitmap, imageFormat)
        } else {
            saveImageToPicturesDirectory(bitmap, imageFormat)
        }
    }

    private fun saveImageWithMediaStore(bitmap: Bitmap, imageFormat: ImageFormat): Uri? {
        val contentValues = ContentValues().apply {
            val displayName = "PDF_Image_${System.currentTimeMillis()}"
            val mimeType = when (imageFormat) {
                ImageFormat.JPEG -> "image/jpeg"
                ImageFormat.PNG -> "image/png"
            }
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PDFConvector")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        return try {
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    val compressFormat = when (imageFormat) {
                        ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
                        ImageFormat.PNG -> Bitmap.CompressFormat.PNG
                    }
                    val quality = if (imageFormat == ImageFormat.JPEG) 90 else 100
                    bitmap.compress(compressFormat, quality, outputStream)
                }
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }
            uri
        } catch (e: Exception) {
            Log.e("ServiceMediaStore", "Ошибка сохранения: ${e.message}")
            null
        }
    }

    private fun saveImageToPicturesDirectory(bitmap: Bitmap, imageFormat: ImageFormat): Uri? {
        return try {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val appDir = File(picturesDir, "PDFConvector")
            if (!appDir.exists()) appDir.mkdirs()

            val fileName = "PDF_Image_${System.currentTimeMillis()}.${imageFormat.extension}"
            val imageFile = File(appDir, fileName)

            FileOutputStream(imageFile).use { outputStream ->
                val compressFormat = when (imageFormat) {
                    ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
                    ImageFormat.PNG -> Bitmap.CompressFormat.PNG
                }
                val quality = if (imageFormat == ImageFormat.JPEG) 90 else 100
                bitmap.compress(compressFormat, quality, outputStream)
            }

            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(imageFile)
            context.sendBroadcast(mediaScanIntent)

            Uri.fromFile(imageFile)
        } catch (e: Exception) {
            Log.e("ServicePictures", "Ошибка сохранения: ${e.message}")
            null
        }
    }

    override fun beginTextBlock() {}
    override fun endTextBlock() {}
    override fun renderText(renderInfo: TextRenderInfo) {}
}

enum class ImageFormat(val extension: String) {
    JPEG("jpg"),
    PNG("png")
}