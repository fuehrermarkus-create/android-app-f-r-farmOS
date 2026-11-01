package at.hof.farmos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

/** Vorschaubilder in einer Reihe; Antippen öffnet die Vollbildansicht. */
@Composable
fun PhotoThumbs(
    urls: List<String>,
    size: Dp = 64.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onRemove: ((Int) -> Unit)? = null,
) {
    if (urls.isEmpty()) return
    var open by remember { mutableStateOf<String?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = contentPadding) {
        itemsIndexed(urls) { i, url ->
            Box {
                AsyncImage(
                    model = url,
                    contentDescription = "Foto",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(size)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { open = url },
                )
                if (onRemove != null) {
                    IconButton(
                        onClick = { onRemove(i) },
                        modifier = Modifier.align(Alignment.TopEnd).size(28.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
                    ) { Icon(Icons.Default.Close, "Entfernen", tint = Color.White) }
                }
            }
        }
    }
    open?.let { PhotoViewer(it) { open = null } }
}

/** Abschnitt „Fotos“ für Detailseiten. */
@Composable
fun PhotoSection(urls: List<String>) {
    if (urls.isEmpty()) return
    Column(Modifier.padding(vertical = 4.dp)) {
        Text("Fotos (${urls.size})", style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        PhotoThumbs(urls, size = 96.dp, contentPadding = PaddingValues(horizontal = 12.dp))
    }
}

/** Vollbild mit Zoomen (zwei Finger) und Doppeltipp zum Zurücksetzen. */
@Composable
fun PhotoViewer(url: String, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = url,
                contentDescription = "Foto",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
                    }
                    .transformable(state)
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y,
                    ),
            )
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Default.Close, "Schließen", tint = Color.White)
            }
        }
    }
}
