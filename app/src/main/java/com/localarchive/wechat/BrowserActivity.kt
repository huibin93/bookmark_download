package com.localarchive.wechat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localarchive.wechat.data.model.LinkSource
import com.localarchive.wechat.data.repository.CaptureLinkResult
import com.localarchive.wechat.ui.ArchiveBrowser
import com.localarchive.wechat.ui.ArchiveTheme
import com.localarchive.wechat.ui.StoredArticleInput

class BrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = archiveApplication.archiveRepository
        val initialUrl = intent.getStringExtra(EXTRA_URL) ?: intent.dataString.orEmpty()
        val suppliedLinkId = intent.getLongExtra(EXTRA_LINK_ID, -1L)
        val archiveDir = intent.getStringExtra(EXTRA_ARCHIVE_DIR)
        val storedTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val storedSourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL).orEmpty()

        setContent {
            ArchiveTheme {
                var linkId by remember { mutableLongStateOf(suppliedLinkId) }
                var url by remember { mutableStateOf(initialUrl) }
                var unsupported by remember { mutableStateOf(false) }

                LaunchedEffect(initialUrl, suppliedLinkId) {
                    if (suppliedLinkId > 0) {
                        repository.getLink(suppliedLinkId)?.let { link ->
                            linkId = link.id
                            url = link.normalizedUrl
                        }
                    } else {
                        when (val result = repository.captureUrl(initialUrl, LinkSource.VIEW_INTENT)) {
                            is CaptureLinkResult.Success -> {
                                linkId = result.link.id
                                url = result.link.normalizedUrl
                            }
                            is CaptureLinkResult.Unsupported -> unsupported = true
                        }
                    }
                }

                if (unsupported || url.isBlank()) {
                    UnsupportedLink(onBackHome = {
                        startActivity(MainActivity.createIntent(this))
                        finish()
                    })
                } else {
                    ArchiveBrowser(
                        repository = repository,
                        initialUrl = storedSourceUrl.ifBlank { url },
                        linkId = linkId.takeIf { it > 0 },
                        autoSaveOnLoad = true,
                        storedArticle = archiveDir?.takeIf { storedSourceUrl.isNotBlank() }?.let {
                            StoredArticleInput(
                                title = storedTitle,
                                sourceUrl = storedSourceUrl,
                                archiveDir = it,
                            )
                        },
                        onClose = { finish() },
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_LINK_ID = "link_id"
        private const val EXTRA_ARCHIVE_DIR = "archive_dir"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SOURCE_URL = "source_url"

        fun createIntent(context: Context, url: String, linkId: Long?): Intent =
            Intent(context, BrowserActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .apply {
                    if (linkId != null) putExtra(EXTRA_LINK_ID, linkId)
                }

        fun createStoredIntent(
            context: Context,
            title: String,
            sourceUrl: String,
            archiveDir: String,
            linkId: Long,
        ): Intent =
            Intent(context, BrowserActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SOURCE_URL, sourceUrl)
                .putExtra(EXTRA_ARCHIVE_DIR, archiveDir)
                .putExtra(EXTRA_LINK_ID, linkId)
    }
}

@Composable
private fun UnsupportedLink(onBackHome: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("不支持的链接类型", style = MaterialTheme.typography.titleLarge)
        Text("第一版只处理 mp.weixin.qq.com 的公众号文章链接。")
        Button(onClick = onBackHome, modifier = Modifier.padding(top = 16.dp)) {
            Text("返回首页")
        }
    }
}
