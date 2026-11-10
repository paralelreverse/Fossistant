package org.fossistant.assistant.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossistant.assistant.model.MediaAttachment
import java.io.File

/**
 * Pictures in a chat bubble: photos the user attached and pictures made with image generation. Tapping one opens
 * it full size; generated pictures ([canSave]) also get Save (to Pictures/Fossistant) and Share buttons.
 */
@Composable
fun ChatImages(
    attachments: List<MediaAttachment>,
    accent: Color,
    canSave: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { attachment -> ChatImage(attachment, accent, canSave) }
    }
}

@Composable
private fun ChatImage(attachment: MediaAttachment, accent: Color, canSave: Boolean) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, attachment.uriString) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(context, attachment.uriString, 720) }
    }
    val shape = RoundedCornerShape(14.dp)
    val image = bitmap
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (image != null && image.height > 0) image.width.toFloat() / image.height else 1f)
                .clip(shape)
                .background(Color(0x33000000))
                .border(1.dp, accent.copy(alpha = 0.35f), shape)
                .clickable { openImage(context, attachment) },
            contentAlignment = Alignment.Center
        ) {
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = attachment.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text("Picture not available", color = Color(0xFF94A3B8), fontSize = 12.sp)
            }
        }
        if (canSave && image != null) {
            Row(modifier = Modifier.padding(top = 6.dp)) {
                ImageAction(Icons.Filled.Download, "Save", accent) { saveToGallery(context, attachment) }
                Spacer(Modifier.width(8.dp))
                ImageAction(Icons.Filled.Share, "Share", accent) { shareImage(context, attachment) }
            }
        }
    }
}

@Composable
private fun ImageAction(icon: ImageVector, label: String, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.30f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = accent, fontSize = 12.sp)
    }
}

private fun localFile(uriString: String): File? {
    val uri = Uri.parse(uriString)
    return if (uri.scheme == null || uri.scheme == "file") File(uri.path ?: uriString) else null
}

private fun decodeThumbnail(context: Context, uriString: String, maxSide: Int): Bitmap? = runCatching {
    val file = localFile(uriString)?.takeIf { it.exists() }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    if (file != null) {
        BitmapFactory.decodeFile(file.absolutePath, bounds)
    } else {
        context.contentResolver.openInputStream(Uri.parse(uriString))?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
    if (file != null) {
        BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
    } else {
        context.contentResolver.openInputStream(Uri.parse(uriString))?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        }
    }
}.getOrNull()

/** A content:// URI other apps can open: generated pictures go through the app's FileProvider. */
private fun shareableUri(context: Context, attachment: MediaAttachment): Uri? {
    val file = localFile(attachment.uriString) ?: return Uri.parse(attachment.uriString)
    if (!file.exists()) return null
    return runCatching { FileProvider.getUriForFile(context, context.packageName + ".files", file) }.getOrNull()
}

private fun openImage(context: Context, attachment: MediaAttachment) {
    val uri = shareableUri(context, attachment) ?: return
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mimeType.ifBlank { "image/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun shareImage(context: Context, attachment: MediaAttachment) {
    val uri = shareableUri(context, attachment) ?: return
    val send = Intent(Intent.ACTION_SEND).setType(attachment.mimeType.ifBlank { "image/png" })
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        context.startActivity(Intent.createChooser(send, "Share picture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun saveToGallery(context: Context, attachment: MediaAttachment) {
    val file = localFile(attachment.uriString)?.takeIf { it.exists() } ?: return
    val saved = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, attachment.mimeType.ifBlank { "image/png" })
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Fossistant")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
    }.isSuccess
    Toast.makeText(context, if (saved) "Saved to Pictures/Fossistant" else "Couldn't save the picture", Toast.LENGTH_SHORT).show()
}
