package com.dailynotes.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.dailynotes.app.diary.DiaryParser

/** 轻量 Markdown 预览：标题/图片行内联/其余原样文本。不做表格代码块（v1 够用） */
@Composable
fun MarkdownPreview(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (raw in text.lines()) {
            val line = raw.trim()
            val img = Regex("""^!\[[^\]]*\]\(([^)]+)\)""").find(line)
            when {
                img != null -> {
                    val ref = DiaryParser.toRootRelative(img.groupValues[1])
                    if (ref.startsWith("attachments/")) {
                        AttachmentImage(
                            imagePath = ref,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(line, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                line.startsWith("###") -> Text(
                    line.trimStart('#', ' '),
                    style = MaterialTheme.typography.titleSmall,
                )
                line.startsWith("##") -> Text(
                    line.trimStart('#', ' '),
                    style = MaterialTheme.typography.titleMedium,
                )
                line.startsWith("#") -> Text(
                    line.trimStart('#', ' '),
                    style = MaterialTheme.typography.titleLarge,
                )
                line.isBlank() -> Spacer(Modifier.height(2.dp))
                else -> Text(line, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
