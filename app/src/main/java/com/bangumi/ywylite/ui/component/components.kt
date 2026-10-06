package com.bangumi.ywylite.ui.component

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bangumi.ywylite.data.model.SubjectSmall

/** 用系统默认浏览器打开链接；无浏览器可处理时不崩溃 */
fun openInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
fun SubjectCard(
    subject: SubjectSmall,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
    ) {
        Column {
            val imageUrl = subject.images?.medium?.takeIf { it.isNotEmpty() }?.replace("http://", "https://")
                ?: subject.image.takeIf { it.isNotEmpty() }?.replace("http://", "https://")
                ?: subject.images?.large?.takeIf { it.isNotEmpty() }?.replace("http://", "https://")
                ?: subject.images?.common?.takeIf { it.isNotEmpty() }?.replace("http://", "https://")
                ?: ""
            AsyncImage(
                model = imageUrl,
                contentDescription = subject.nameCn.ifEmpty { subject.name },
                modifier = Modifier
                    .fillMaxWidth()
                    // 瀑布流 item 高度约束为无穷：无纵横比时 AsyncImage 测量高度为 0，
                    // Coil 拿不到有效尺寸不会发起请求，图片区域塌陷表现为“从未加载”。
                    // 固定纵横比让首帧即有确定尺寸，加载请求立即发出
                    .aspectRatio(5f / 7f),
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
            )
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = subject.nameCn.ifEmpty { subject.name },
                    style = MaterialTheme.typography.bodySmall
                )
                subject.rating?.let { rating ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "★${rating.score}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SubjectListItem(
    subject: SubjectSmall,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        headlineContent = {
            Text(
                text = subject.nameCn.ifEmpty { subject.name },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (subject.nameCn.isNotEmpty()) {
                    Text(
                        text = subject.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                subject.rating?.let { rating ->
                    Text(
                        text = "★${rating.score}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        leadingContent = {
            val imageUrl = subject.images?.medium?.takeIf { it.isNotEmpty() }?.replace("http://", "https://")
                ?: subject.image.takeIf { it.isNotEmpty() }?.replace("http://", "https://")
                ?: subject.images?.large?.takeIf { it.isNotEmpty() }?.replace("http://", "https://")
                ?: subject.images?.common?.takeIf { it.isNotEmpty() }?.replace("http://", "https://")
                ?: ""
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Crop
            )
        },
        modifier = modifier.clickable(onClick = onClick)
    )
}

@Composable
fun LoadingView(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
            OutlinedButton(onClick = onRetry) {
                Text("重试")
            }
        }
    }
}

@Composable
fun EmptyView(
    message: String = "暂无数据",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun CopyableText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    modifier: Modifier = Modifier
) {
    SelectionContainer {
        Text(
            text = text,
            style = style,
            modifier = modifier
        )
    }
}
