package com.localarchive.wechat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.localarchive.wechat.data.db.ArticleEntity
import com.localarchive.wechat.ui.ArchiveApp
import com.localarchive.wechat.ui.ArchiveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repository = archiveApplication.archiveRepository
        setContent {
            ArchiveTheme {
                ArchiveApp(
                    repository = repository,
                    onOpenRemoteLink = { link ->
                        startActivity(BrowserActivity.createIntent(this, link.normalizedUrl, link.id))
                    },
                    onOpenStoredArticle = { article: ArticleEntity ->
                        startActivity(
                            BrowserActivity.createStoredIntent(
                                context = this,
                                title = article.title,
                                sourceUrl = article.normalizedUrl,
                                archiveDir = article.archiveDir,
                                linkId = article.linkId,
                            ),
                        )
                    },
                )
            }
        }
    }

    companion object {
        fun createIntent(context: android.content.Context): Intent =
            Intent(context, MainActivity::class.java)
    }
}
