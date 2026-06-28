package com.localarchive.wechat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.localarchive.wechat.data.model.LinkSource
import com.localarchive.wechat.data.repository.CaptureLinkResult
import kotlinx.coroutines.launch

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        lifecycleScope.launch {
            val result = archiveApplication.archiveRepository.captureIncomingText(sharedText, LinkSource.SHARE)
            when (result) {
                is CaptureLinkResult.Success -> {
                    startActivity(BrowserActivity.createIntent(this@ShareReceiverActivity, result.link.normalizedUrl, result.link.id))
                }
                is CaptureLinkResult.Unsupported -> {
                    startActivity(MainActivity.createIntent(this@ShareReceiverActivity))
                }
            }
            finish()
        }
    }
}
