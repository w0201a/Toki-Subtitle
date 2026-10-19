package io.github.meiyongai.toki.subtitle.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import io.github.meiyongai.toki.subtitle.ui.screen.SubtitleDialog

/** 播放页调用的独立设置窗口；只显示编辑界面，不接收外部配置写入参数。 */
class SubtitleSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SubtitleDialog { finish() } } }
    }
}
