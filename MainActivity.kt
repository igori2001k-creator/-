package com.example.pdfconvector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible

class MainActivity : AppCompatActivity() {
    private lateinit var selectFileBtn: Button
    private lateinit var extractBtn: Button
    private lateinit var viewImagesBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var sizeSeekBar: SeekBar
    private lateinit var sizeTextView: TextView

    private var selectedPdfUri: Uri? = null
    private var minImageSizeBytes: Int = 10 * 1024

    private lateinit var viewModel: SharedViewModel
    private lateinit var filePicker: ActivityResultLauncher<Array<String>>

    // Launcher для запроса разрешения на уведомления
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Обработка ответа пользователя
        if (isGranted) {
            // Разрешение предоставлено. Вы можете показывать уведомления.
            Toast.makeText(this, "Разрешение на уведомления предоставлено", Toast.LENGTH_SHORT).show()
        } else {
            // Разрешение не предоставлено. Уведомления показывать нельзя.
            Toast.makeText(this, "Вы можете включить уведомления в настройках позже", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация ViewModel
        viewModel = (application as PdfConvectorApp).sharedViewModel

        filePicker = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            uri?.let {
                selectedPdfUri = it
                Toast.makeText(this, "Файл выбран: ${it.lastPathSegment}", Toast.LENGTH_SHORT).show()
                extractBtn.isEnabled = true
            }
        }

        initViews()
        setupClickListeners()
        setupSeekBar()
        observeViewModel()

        // Запрашиваем разрешение при создании активности
        requestNotificationPermission()
    }

    private fun initViews() {
        selectFileBtn = findViewById(R.id.selectFileBtn)
        extractBtn = findViewById(R.id.extractBtn)
        viewImagesBtn = findViewById(R.id.viewImagesBtn)
        progressBar = findViewById(R.id.progressBar)
        sizeSeekBar = findViewById(R.id.sizeSeekBar)
        sizeTextView = findViewById(R.id.sizeTextView)

        extractBtn.isEnabled = false
        viewImagesBtn.isVisible = false
        progressBar.isVisible = false
    }

    private fun setupSeekBar() {
        sizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                minImageSizeBytes = progress * 1024
                sizeTextView.text = "Минимальный размер изображений: $progress КБ"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupClickListeners() {
        selectFileBtn.setOnClickListener {
            filePicker.launch(arrayOf("application/pdf"))
        }

        extractBtn.setOnClickListener {
            selectedPdfUri?.let { uri ->
                startImageExtraction(uri)
            } ?: run {
                Toast.makeText(this, "Сначала выберите файл", Toast.LENGTH_SHORT).show()
            }
        }

        viewImagesBtn.setOnClickListener {
            viewModel.extractedImages.value?.let { images ->
                if (images.isNotEmpty()) {
                    val intent = Intent(this, ImageGalleryActivity::class.java).apply {
                        putParcelableArrayListExtra("imageUris", ArrayList(images))
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Нет извлеченных изображений", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun observeViewModel() {
        // Наблюдаем за прогрессом извлечения
        viewModel.extractionProgress.observe(this) { progress ->
            progressBar.progress = progress
        }

        // Наблюдаем за состоянием извлечения
        viewModel.isExtractionRunning.observe(this) { isRunning ->
            progressBar.isVisible = isRunning
            extractBtn.isEnabled = !isRunning
            if (!isRunning) {
                progressBar.progress = 0
            }
        }

        // Наблюдаем за сообщениями
        viewModel.extractionMessage.observe(this) { message ->
            if (message.isNotEmpty()) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }

        // Наблюдаем за списком изображений
        viewModel.extractedImages.observe(this) { images ->
            viewImagesBtn.isVisible = images.isNotEmpty()
        }
    }

    private fun startImageExtraction(pdfUri: Uri) {
        // Очищаем предыдущие результаты
        viewModel.clearExtractedImages()
        viewModel.setExtractionRunning(true)

        val serviceIntent = Intent(this, PdfExtractionService::class.java).apply {
            putExtra("PDF_URI", pdfUri.toString())
            putExtra("MIN_SIZE_BYTES", minImageSizeBytes)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        Toast.makeText(this, "Извлечение началось...", Toast.LENGTH_SHORT).show()
    }

    // Функция для проверки и запроса разрешения
    private fun requestNotificationPermission() {
        // Проверяем версию Android (разрешение нужно только с API 33)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Разрешение уже есть
                }
                ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) -> {
                    // Здесь можно показать пользователю объяснение, зачем нужно разрешение
                    showPermissionExplanationDialog()
                }
                else -> {
                    // Запрашиваем разрешение
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // На версиях ниже Android 13 разрешение не требуется
        }
    }

    // Показ диалога с объяснением (опционально, но рекомендуется)
    private fun showPermissionExplanationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Нужны уведомления")
            .setMessage("Чтобы вы знали о ходе извлечения изображений, когда приложение свёрнуто, разрешите показ уведомлений.")
            .setPositiveButton("Хорошо") { _, _ ->
                // Пользователь понял, запрашиваем разрешение
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton("Не сейчас", null)
            .show()
    }
}