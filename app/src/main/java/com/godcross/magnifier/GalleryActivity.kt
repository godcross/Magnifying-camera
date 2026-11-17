package com.godcross.magnifier

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.godcross.magnifier.databinding.ActivityGalleryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private var photos: List<Photo> = emptyList()
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.grid.layoutManager = GridLayoutManager(this, 3)
        binding.grid.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            photos = withContext(Dispatchers.IO) { PhotoStore.list(this@GalleryActivity) }
            binding.toolbar.title = "Photos (${photos.size})"
            binding.emptyText.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
            @Suppress("NotifyDataSetChanged")
            adapter.notifyDataSetChanged()
        }
    }

    private inner class Holder(val image: ImageView) : RecyclerView.ViewHolder(image) {
        var job: Job? = null
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false) as ImageView
            return Holder(v)
        }

        override fun getItemCount() = photos.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val photo = photos[position]
            val key = photo.uri.toString()
            holder.job?.cancel()
            holder.image.setOnClickListener {
                startActivity(
                    Intent(this@GalleryActivity, ViewerActivity::class.java)
                        .putExtra(ViewerActivity.EXTRA_INDEX, holder.bindingAdapterPosition),
                )
            }
            val cached = cache.get(key)
            if (cached != null) {
                holder.image.setImageBitmap(cached)
                return
            }
            holder.image.setImageDrawable(null)
            holder.job = lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { PhotoStore.loadThumbnail(this@GalleryActivity, photo.uri, 320) }
                if (bmp != null) {
                    cache.put(key, bmp)
                    holder.image.setImageBitmap(bmp)
                }
            }
        }
    }
}
