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
import com.dailynotes.app.data.FileCache

/** 附件图片加载（v0.3.0 File 模型）：文件在 App 私有目录，存在性本地 stat（微秒级），
 *  直接给 Coil File 模型，无 SAF 寻址。 */
@Composable
fun AttachmentImage(
    imagePath: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val file = remember(imagePath) {
        runCatching { FileCache(context).file(imagePath) }.getOrNull()
    }
    file?.let {
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
