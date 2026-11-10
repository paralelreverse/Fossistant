package org.fossistant.assistant.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.fossistant.assistant.model.BlobMoodTheme
import java.io.ByteArrayOutputStream

/**
 * Gemini-Live-style Embedded Camera Viewfinder that streams real-time visual frames directly into
 * Fossistant's multimodal context (`Gemma 4 E2B Vision`) during voice or text conversations without
 * leaving the assistant popup or app.
 */
@Composable
fun CameraLiveViewfinder(
    moodTheme: BlobMoodTheme = BlobMoodTheme.COSMIC_NEBULA,
    onFrameCaptured: (ByteArray) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    heightDp: Int = 225
) {
    val context = LocalContext.current
    var useFrontCamera by remember { mutableStateOf(false) }
    var framesStreamedCount by remember { mutableIntStateOf(0) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

    val controller = remember { Camera2PreviewController(context) { err -> cameraError = err } }

    DisposableEffect(useFrontCamera) {
        textureViewRef?.let { tv ->
            controller.startCamera(tv, useFrontCamera)
        }
        onDispose {
            controller.stopCamera()
        }
    }

    // Periodically capture downscaled JPEG frames from the live TextureView so Gemma 4 E2B
    // always has the latest real-time camera frame ready when the user speaks or asks a question.
    LaunchedEffect(textureViewRef, useFrontCamera) {
        delay(900)
        while (isActive) {
            val tv = textureViewRef
            if (tv != null && tv.isAvailable) {
                val jpeg = captureJpegFromTextureView(tv)
                if (jpeg != null && jpeg.isNotEmpty()) {
                    onFrameCaptured(jpeg)
                    framesStreamedCount++
                }
            }
            delay(2400)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .shadow(18.dp, RoundedCornerShape(24.dp), spotColor = moodTheme.secondaryAccent)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF090C14))
            .border(
                width = 1.6.dp,
                brush = Brush.linearGradient(
                    listOf(moodTheme.primaryAccent, moodTheme.secondaryAccent)
                ),
                shape = RoundedCornerShape(24.dp)
            )
    ) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            textureViewRef = this@apply
                            controller.configureTransform(this@apply, width, height)
                            controller.startCamera(this@apply, useFrontCamera)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            controller.configureTransform(this@apply, width, height)
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            controller.stopCamera()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Top Overlay Bar: Live Streaming Badge + Flip Camera + Close Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xCC000000), Color.Transparent)
                    )
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xCC111827))
                    .border(1.dp, moodTheme.secondaryAccent, RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF10B981))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "LIVE VISION • ${if (framesStreamedCount > 0) "Streaming ($framesStreamedCount)" else "Ready"}",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Manual Immediate Frame Capture Button
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC1E293B))
                        .border(1.dp, moodTheme.secondaryAccent, CircleShape)
                        .clickable {
                            textureViewRef?.let { tv ->
                                captureJpegFromTextureView(tv)?.let { jpeg ->
                                    onFrameCaptured(jpeg)
                                    framesStreamedCount++
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = "Capture Frame Now",
                        tint = moodTheme.secondaryAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Flip Front / Back Camera
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC1E293B))
                        .border(1.dp, Color(0x55FFFFFF), CircleShape)
                        .clickable { useFrontCamera = !useFrontCamera },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Switch Camera",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Close Live Viewfinder
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC3B0D1E))
                        .border(1.dp, Color(0xFFEF4444), CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Camera",
                        tint = Color(0xFFFCA5A5),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Bottom Hint / Error Banner
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xDD000000))
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (cameraError != null) {
                Text(
                    text = "⚠ $cameraError",
                    color = Color(0xFFFCA5A5),
                    fontSize = 11.sp
                )
            } else {
                Text(
                    text = "Talk or type naturally — real-time camera frames are sent with your prompt",
                    color = Color(0xFFE2E8F0),
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * Grabs the current camera frame. `getBitmap` ignores the view's crop transform and stretches the frame to the
 * size asked for, so it is requested at the camera's own shape (640x480 sensor output, upright in portrait =
 * 480x640) rather than the wide viewfinder box, which used to squash every frame Gemma saw.
 */
private fun captureJpegFromTextureView(textureView: TextureView): ByteArray? {
    return runCatching {
        val portrait = textureView.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val bmp: Bitmap = (if (portrait) textureView.getBitmap(480, 640) else textureView.getBitmap(640, 480)) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        bmp.recycle()
        out.toByteArray()
    }.getOrNull()
}

private class Camera2PreviewController(
    private val context: Context,
    private val onError: (String) -> Unit
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    fun configureTransform(textureView: TextureView, viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val matrix = android.graphics.Matrix()
        val previewRatio = 3f / 4f
        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()
        val scaleX: Float
        val scaleY: Float
        if (viewRatio > previewRatio) {
            scaleX = 1f
            scaleY = (viewWidth / previewRatio) / viewHeight.toFloat()
        } else {
            scaleX = (viewHeight.toFloat() * previewRatio) / viewWidth.toFloat()
            scaleY = 1f
        }
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        textureView.setTransform(matrix)
    }

    @SuppressLint("MissingPermission")
    fun startCamera(textureView: TextureView, preferFront: Boolean) {
        stopCamera()
        val surfaceTexture = textureView.surfaceTexture ?: return
        startBackgroundThread()

        runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val targetFacing = if (preferFront) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == targetFacing
            } ?: manager.cameraIdList.firstOrNull()

            if (cameraId == null) {
                onError("No camera found on this device.")
                return
            }

            surfaceTexture.setDefaultBufferSize(640, 480)
            val previewSurface = Surface(surfaceTexture)

            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        runCatching {
                            val reqBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            reqBuilder.addTarget(previewSurface)
                            reqBuilder.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            @Suppress("DEPRECATION")
                            camera.createCaptureSession(
                                listOf(previewSurface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        if (cameraDevice == null) return
                                        captureSession = session
                                        runCatching {
                                            session.setRepeatingRequest(
                                                reqBuilder.build(),
                                                null,
                                                backgroundHandler
                                            )
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        onError("Camera preview session configuration failed.")
                                    }
                                },
                                backgroundHandler
                            )
                        }.onFailure { e ->
                            onError("Camera preview error: ${e.message}")
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        onError("Camera hardware error ($error).")
                    }
                },
                backgroundHandler
            )
        }.onFailure { e ->
            onError("Failed to open camera: ${e.message}")
        }
    }

    fun stopCamera() {
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("FossistantCamera2Thread").also {
                it.start()
                backgroundHandler = Handler(it.looper)
            }
        }
    }

    private fun stopBackgroundThread() {
        runCatching {
            backgroundThread?.quitSafely()
            backgroundThread?.join(250)
        }
        backgroundThread = null
        backgroundHandler = null
    }
}
