package io.github.ravenor907.muninn.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import io.github.ravenor907.muninn.net.Gifs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * An animated GIF in a chat (cap a1). It moves on Android 9 and later (AnimatedImageDrawable); before that, and when it
 * can't be decoded, its first frame shows. With Less motion (or the phone's animations off) it holds still with a play
 * button. It stops whenever it's off screen or the app is in the background.
 *
 * Decoding is kept on a short leash: the bytes are checked again against the GIF limits (Gifs.clean) before any
 * decoder sees them, decoding happens off the main thread at no more than [maxSide] pixels, and any failure (an
 * exception or running out of memory) just leaves the still or the word "GIF".
 */
@Composable
fun GifImage(id: String, bytes: () -> ByteArray?, description: String, modifier: Modifier = Modifier, fit: Boolean = false, maxSide: Int = 720) {
    val context = LocalContext.current
    val still = Access.reduceMotion || remember(context) { animationsOff(context) }
    var asked by remember(id) { mutableStateOf(false) }
    val pic by produceState<GifPic?>(null, id, maxSide) { value = withContext(Dispatchers.Default) { GifPic.load(bytes(), maxSide) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val watch = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) foreground = true else if (e == Lifecycle.Event.ON_STOP) foreground = false
        }
        lifecycle.addObserver(watch)
        onDispose { lifecycle.removeObserver(watch) }
    }
    val p = pic
    val moving = p?.moving
    val play = moving != null && foreground && (!still || asked)
    Box(modifier.semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        when {
            moving != null -> {
                AndroidView(factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = if (fit) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                }, update = { v ->
                    if (v.drawable !== moving) v.setImageDrawable(moving)
                    val a = moving as? Animatable
                    if (play) a?.start() else a?.stop()
                }, modifier = Modifier.fillMaxSize())
                // Off screen (scrolled away, or the chat closed): it stops.
                DisposableEffect(moving) { onDispose { (moving as? Animatable)?.stop() } }
            }
            p?.first != null -> Image(p.first, contentDescription = null, contentScale = if (fit) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> Text("GIF", style = Type.small)
        }
        // Less motion: still until asked, and asked again to stop.
        if (moving != null && still) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0x99000000)).clickable(role = Role.Button) { asked = !asked }
                .semantics { contentDescription = if (asked) "Stop the GIF" else "Play the GIF" }, contentAlignment = Alignment.Center) {
                MIcon(if (asked) "pausefill" else "play", Color.White, 24.dp, filled = true)
            }
        }
        if (p != null) Text("GIF", style = Type.small.copy(color = Color.White, fontSize = 11.sp),
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x99000000))
                .padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

/** The phone's own "Remove animations" (animator duration scale 0). */
private fun animationsOff(context: Context): Boolean = try {
    android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
} catch (_: Exception) { false }

/** A decoded GIF: [moving] when it can play (Android 9 and later), else only its [first] frame. */
private class GifPic(val moving: Drawable?, val first: ImageBitmap?) {
    companion object {
        fun load(raw: ByteArray?, maxSide: Int): GifPic? {
            if (raw == null) return null
            // The limits again, whatever is stored: a decoder only ever sees a GIF that passed them.
            val gif = Gifs.cleanOrNull(raw) ?: return null
            val moving = if (Build.VERSION.SDK_INT >= 28) animated(gif, maxSide) else null
            return GifPic(moving, if (moving == null) firstFrame(gif, maxSide) else null)
        }

        @RequiresApi(28)
        private fun animated(gif: ByteArray, maxSide: Int): Drawable? = try {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(gif))) { decoder, info, _ ->
                val long = maxOf(info.size.width, info.size.height)
                if (long > maxSide) {
                    val f = maxSide.toFloat() / long
                    decoder.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
                }
            }.takeIf { it is Animatable }
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }

        private fun firstFrame(gif: ByteArray, maxSide: Int): ImageBitmap? = try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(gif, 0, gif.size, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeByteArray(gif, 0, gif.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
    }
}
