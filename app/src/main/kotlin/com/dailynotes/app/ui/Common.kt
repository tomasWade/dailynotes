package com.dailynotes.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dailynotes.app.data.SafCache
import com.dailynotes.app.data.SettingsStore

/** 附件图片加载：uri 优先走进程缓存（列表滑动零 SAF IPC），miss 才解析并回填（含负缓存） */
@Composable
fun AttachmentImage(
    imagePath: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val uri = remember(imagePath) {
        val cached = com.dailynotes.app.data.AttachmentUriCache.get(imagePath)
        if (cached != null) {
            cached.takeIf { it != Uri.EMPTY }
        } else {
            val resolved = runCatching {
                val treeUri = SettingsStore(context).treeUri()
                if (treeUri.isBlank()) null
                else SafCache(context, treeUri).uri(imagePath)
            }.getOrNull()
            com.dailynotes.app.data.AttachmentUriCache.put(imagePath, resolved)
            resolved
        }
    }
    uri?.let {
        AsyncImage(
            model = it,
            contentDescription = "配图",
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}

/** 卡片首图（固定高度裁切） */
@Composable
fun EntryThumb(imagePath: String) {
    AttachmentImage(
        imagePath = imagePath,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(8.dp)),
    )
}
