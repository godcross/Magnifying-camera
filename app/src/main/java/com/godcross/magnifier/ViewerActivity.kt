package com.godcross.magnifier

import android.app.RecoverableSecurityException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.godcross.magnifier.databinding.ActivityViewerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/** Full-resolution viewer: pinch up to 12×, swipe left/right between photos. */
class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private var photos: List<Photo> = emptyList()
    private var index = 0
    private var loadJob: Job? = null
    private var barsVisible = true

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) afterDelete()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val top = binding.topBar.paddingTop
        val bottom = binding.bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = top + bars.top)
            binding.bottomBar.updatePadding(bottom = bottom + bars.bottom)
            insets
        }

        index = intent.getIntExtra(EXTRA_INDEX, 0)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnShare.setOnClickListener { share() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.image.onSwipe = { dir -> show(index + dir) }
        binding.image.onSingleTap = { toggleBars() }

        lifecycleScope.launch {
            photos = withContext(Dispatchers.IO) { PhotoStore.list(this@ViewerActivity) }
            if (photos.isEmpty()) finish() else show(index)
        }
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        val v = if (barsVisible) View.VISIBLE else View.GONE
        binding.topBar.visibility = v
        binding.bottomBar.visibility = v
    }

    private fun show(i: Int) {
        if (photos.isEmpty()) return
        if (i !in photos.indices) return
        index = i
        val photo = photos[i]
        binding.infoText.text = buildString {
            append("${i + 1} / ${photos.size}   ")
            if (photo.width > 0) append("${photo.width}×${photo.height}   ")
            append(Formatter.formatShortFileSize(this@ViewerActivity, photo.sizeBytes))
            append("\n")
            append(DateFormat.getMediumDateFormat(this@ViewerActivity).format(Date(photo.dateMillis)))
            append(" ")
            append(DateFormat.getTimeFormat(this@ViewerActivity).format(Date(photo.dateMillis)))
        }
        binding.progress.visibility = View.VISIBLE
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    PhotoStore.decodeSampled(this@ViewerActivity, photo.uri, 4096)
                } catch (e: Throwable) {
                    null
                }
            }
            binding.progress.visibility = View.GONE
            if (bmp != null) {
                binding.image.setImageBitmap(bmp)
            } else {
                Toast.makeText(this@ViewerActivity, "Couldn't open photo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun share() {
        val photo = photos.getOrNull(index) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, photo.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Send photo"))
    }

    private fun confirmDelete() {
        val photo = photos.getOrNull(index) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete photo?")
            .setMessage(photo.name)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> delete(photo) }
            .show()
    }

    private fun delete(photo: Photo) {
        try {
            val rows = contentResolver.delete(photo.uri, null, null)
            if (rows > 0) afterDelete()
        } catch (e: SecurityException) {
            // Photo not owned by this install (e.g. after reinstall): ask Android for permission.
            val sender = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    MediaStore.createDeleteRequest(contentResolver, listOf(photo.uri)).intentSender
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException ->
                    e.userAction.actionIntent.intentSender
                else -> null
            }
            if (sender != null) {
                deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } else {
                Toast.makeText(this, "Delete not allowed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun afterDelete() {
        lifecycleScope.launch {
            photos = withContext(Dispatchers.IO) { PhotoStore.list(this@ViewerActivity) }
            if (photos.isEmpty()) {
                finish()
            } else {
                show(index.coerceAtMost(photos.size - 1))
            }
        }
    }

    companion object {
        const val EXTRA_INDEX = "index"
    }
}
