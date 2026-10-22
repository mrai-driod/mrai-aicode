package com.aicode.feature.settings.presentation.component

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.settings.data.repository.BackgroundSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes the background image sampled to the target size (inSampleSize power-of-two scaling, to control peak memory); returns null on failure.
 * Shared by the global background layer and the settings sheet thumbnail.
 */
internal fun decodeBackgroundBitmap(path: String, maxWidth: Int, maxHeight: Int): ImageBitmap? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxWidth * 2 || bounds.outHeight / sample > maxHeight * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
    }.getOrNull()

/**
 * Custom background image settings sheet: thumbnail preview + opacity slider + pick/remove image.
 *
 * @param imagePath Current background image path; null means not set.
 * @param alpha Current opacity (0.05~1.0).
 * @param onPickImage Callback after the user picks an image.
 * @param onAlphaChange Opacity change callback (writes DataStore, and the global background changes with it; write throttling is handled on the ViewModel side).
 * @param onRemove Callback to remove the background.
 * @param onDismiss Callback to dismiss the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackgroundImageSheet(
    imagePath: String?,
    alpha: Float,
    onPickImage: (Uri) -> Unit,
    onAlphaChange: (Float) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onPickImage(uri) }

    // Thumbnail preview: sampled to screen width to avoid fully decoding large images
    val previewBitmap by produceState<ImageBitmap?>(initialValue = null, imagePath) {
        value = imagePath?.let { path ->
            withContext(Dispatchers.IO) {
                decodeBackgroundBitmap(path, 1024, 1024)
            }
        }
    }

    // The slider position is driven by local state: binding it directly to the external alpha would make every drag go through a "write DataStore -> read back -> recompose the whole settings page"
    // round trip, so the slider could not keep up with the finger. The sheet is rebuilt every time it opens, so taking the initial value from the current setting is enough.
    var sliderPercent by remember { mutableFloatStateOf(BackgroundSettingsRepository.alphaToSlider(alpha)) }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.settings_background_image),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.md)
            )

            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap!!,
                    contentDescription = stringResource(R.string.settings_background_image),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(Spacing.lg))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_background_image_alpha),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${sliderPercent.toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Slider(
                value = sliderPercent,
                onValueChange = { percent ->
                    sliderPercent = percent
                    onAlphaChange(BackgroundSettingsRepository.sliderToAlpha(percent))
                },
                valueRange = 0f..100f
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Button(
                    onClick = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.settings_background_image_pick))
                }
                OutlinedButton(
                    onClick = {
                        onRemove()
                        onDismiss()
                    },
                    enabled = imagePath != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.settings_background_image_remove))
                }
            }
        }
    }
}
