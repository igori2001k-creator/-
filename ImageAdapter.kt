package com.example.pdfconvector
import android.net.Uri
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import com.bumptech.glide.Glide


class ImageAdapter(
    private val context: Context,
    private val imageUris: List<Uri>
) : BaseAdapter() {

    override fun getCount(): Int = imageUris.size

    override fun getItem(position: Int): Uri = imageUris[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val imageView: ImageView

        if (convertView == null) {
            // Создаем новый ImageView если convertView равен null
            imageView = ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(250, 250)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
        } else {
            // Переиспользуем существующий View
            imageView = convertView as ImageView
        }

        // Используем Glide для асинхронной загрузки
        Glide.with(context)
            .load(imageUris[position]) // Загружаем по Uri
            .centerCrop() // Подгоняем изображение под размер ImageView
            .into(imageView) // Указываем целевой ImageView

        return imageView // Возвращаем imageView, а не convertView
    }
}