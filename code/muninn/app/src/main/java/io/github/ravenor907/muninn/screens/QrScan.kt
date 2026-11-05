package io.github.ravenor907.muninn.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import io.github.ravenor907.muninn.ui.Corner
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import java.util.concurrent.Executors

/**
 * The back camera, looking for a QR code. The picture never leaves the phone and nothing is saved: each frame is
 * read for a code and dropped. [onCode] gets the first code's text (once); after that the camera stops.
 */
@Composable
fun QrScanner(modifier: Modifier = Modifier, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(granted()) }
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> allowed = ok || granted(); refused = !allowed }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }
    val found by rememberUpdatedState(onCode)
    Box(modifier.clip(Corner).background(Palette.groundDeep).border(1.dp, Palette.line, Corner), contentAlignment = Alignment.Center) {
        if (!allowed) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (refused) "Scanning needs the camera. You can still show your code or paste theirs below." else "Muninn needs the camera to scan a code.",
                    style = Type.small, textAlign = TextAlign.Center)
                MButton("Allow the camera", Modifier.padding(top = 8.dp), Kind.Accent) { ask.launch(Manifest.permission.CAMERA) }
            }
            return@Box
        }
        val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
        AndroidView({ preview }, Modifier.fillMaxSize().semantics { contentDescription = "Camera, looking for a code" })
        // A plain square shows where to hold the code.
        Box(Modifier.size(200.dp).border(2.dp, Palette.goldBright, Corner))
        DisposableEffect(Unit) {
            val worker = Executors.newSingleThreadExecutor()
            var done = false
            val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(worker) { img ->
                try {
                    if (done) return@setAnalyzer
                    // The Y plane is the picture in grey, which is all a QR code needs.
                    val plane = img.planes[0]
                    val buf = plane.buffer
                    val data = ByteArray(buf.remaining()).also { buf.get(it) }
                    val src = PlanarYUVLuminanceSource(data, plane.rowStride, img.height, 0, 0, img.width, img.height, false)
                    val text = try { reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text } catch (e: Exception) { null } finally { reader.reset() }
                    if (!text.isNullOrBlank()) { done = true; preview.post { found(text) } }
                } finally { img.close() }
            }
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val provider = future.get()
                    val p = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, p, analysis)
                } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(context))
            onDispose {
                done = true
                try { ProcessCameraProvider.getInstance(context).get().unbindAll() } catch (_: Exception) {}
                worker.shutdown()
            }
        }
    }
}
