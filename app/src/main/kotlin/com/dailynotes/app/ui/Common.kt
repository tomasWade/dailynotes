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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dailynotes.app.data.SafCache
import com.dailynotes.app.data.SettingsStore

/** 附件图片加载：SAF 树解析 content uri 喂给 Coil */
@Composable
fun AttachmentImage(
    imagePath: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val uri = remember(imagePath) {
        runCatching {
            val treeUri = SettingsStore(context).treeUri()
            if (treeUri.isBlank()) null
            else SafCache(context, treeUri).uri(imagePath)
        }.getOrNull()
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
