package io.github.meiyongai.toki.subtitle.ui

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.ui.component.stableDialogHeight
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 字幕版赞助入口，由 154Er 与 Gi-Ai154M 共同维护；保存用户所选渠道的原始 JPEG。 */
@Composable
fun SubtitleSponsorDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val strings = context.resources
    val scope = rememberCoroutineScope()
    var wechat by remember { mutableStateOf(true) }
    var exportWechat by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val saveImage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) {
            val resource = if (exportWechat) R.drawable.sponsor_wechat_154er else R.drawable.sponsor_alipay_154er
            saving = true
            message = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        exportOriginalImage(
                            openInput = { context.resources.openRawResource(resource) },
                            openOutput = { context.contentResolver.openOutputStream(uri, "w") }
                        )
                    }
                    message = strings.getString(R.string.sponsor_image_saved)
                } catch (error: IOException) {
                    Log.e("TokiImageExport", "Sponsor image export failed", error)
                    message = strings.getString(R.string.sponsor_error_io)
                } catch (error: SecurityException) {
                    message = strings.getString(R.string.sponsor_error_permission)
                } finally {
                    saving = false
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.getString(R.string.subtitle_sponsor_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().stableDialogHeight(520.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(strings.getString(R.string.subtitle_sponsor_hint), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(selected = wechat, onClick = { wechat = true; message = null },
                        label = { Text(strings.getString(R.string.subtitle_sponsor_wechat)) })
                    FilterChip(selected = !wechat, onClick = { wechat = false; message = null },
                        label = { Text(strings.getString(R.string.sponsor_alipay)) })
                }
                Image(
                    painterResource(if (wechat) R.drawable.sponsor_wechat_154er else R.drawable.sponsor_alipay_154er),
                    contentDescription = strings.getString(if (wechat) R.string.subtitle_sponsor_wechat else R.string.sponsor_alipay_qr),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(360.dp)
                )
                TextButton(enabled = !saving, onClick = {
                    exportWechat = wechat
                    saveImage.launch(if (wechat) "Toki-154Er-WeChat.jpg" else "Toki-154Er-Alipay.jpg")
                }) {
                    Text(strings.getString(if (saving) R.string.sponsor_saving else R.string.sponsor_save_image))
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("154Er · Gi-Ai154M", style = MaterialTheme.typography.labelMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.getString(R.string.common_close)) } }
    )
}
