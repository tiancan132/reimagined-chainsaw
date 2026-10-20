package com.duyue.reader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duyue.reader.model.Book

/** 书架首页 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    books: List<Book>,
    theme: ReaderTheme,
    onOpenBook: (Book) -> Unit,
    onImport: () -> Unit,
    onOpenSettings: () -> Unit,
    onDeleteBook: (Book) -> Unit = {}
) {
    // 长按后等待确认删除的书
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    // 删除确认对话框
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = theme.bg,
            title = {
                Text("删除书籍", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = theme.text)
            },
            text = {
                Text(
                    "确定要删除《${target.title}》吗？\n\n这本书的正文和阅读进度都会被清除，源文件不受影响。",
                    fontSize = 14.sp,
                    color = theme.text,
                    lineHeight = 21.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteBook(target)
                        pendingDelete = null
                    }
                ) {
                    Text("删除", color = Color(0xFFE05C5C), fontSize = 14.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = theme.sub, fontSize = 14.sp)
                }
            }
        )
    }
    Scaffold(
        containerColor = theme.bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("读阅", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = theme.text)
                        Text("听书 · 字幕 · AI 预习", fontSize = 12.sp, color = theme.sub)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置", tint = theme.text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = theme.bg)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onImport,
                containerColor = theme.accent,
                contentColor = theme.bg,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("导入书籍") }
            )
        }
    ) { padding ->
        if (books.isEmpty()) {
            EmptyShelf(theme, padding, onImport)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(books, key = { it.id }) { book ->
                    BookRow(
                        book = book,
                        theme = theme,
                        onClick = { onOpenBook(book) },
                        onLongClick = { pendingDelete = book }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyShelf(theme: ReaderTheme, padding: PaddingValues, onImport: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📖", fontSize = 56.sp)
            Spacer(Modifier.height(16.dp))
            Text("书架还是空的", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = theme.text)
            Spacer(Modifier.height(8.dp))
            Text(
                "支持 TXT / EPUB / PDF\n点右下角导入本地书籍",
                fontSize = 14.sp,
                color = theme.sub,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 22.sp
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onImport,
                colors = ButtonDefaults.buttonColors(containerColor = theme.accent, contentColor = theme.bg)
            ) {
                Text("选择文件")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    book: Book,
    theme: ReaderTheme,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val cardBg = when (theme) {
        ReaderTheme.NIGHT -> androidx.compose.ui.graphics.Color(0xFF23252A)
        ReaderTheme.GREEN -> androidx.compose.ui.graphics.Color(0xFFC9DEC4)
        ReaderTheme.SEPIA -> androidx.compose.ui.graphics.Color(0xFFE3D2B4)
        else -> androidx.compose.ui.graphics.Color(0xFFFFFDF8)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cardBg, RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面占位：按格式配色
        Box(
            modifier = Modifier
                .size(width = 54.dp, height = 74.dp)
                .background(theme.accent.copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = book.format.name,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = theme.accent
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                book.title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = theme.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${book.author} · ${book.chapters.size} 章",
                fontSize = 12.sp,
                color = theme.sub
            )
            if (book.lastReadChapter > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "上次读到：${book.chapters.getOrNull(book.lastReadChapter)?.title ?: "第${book.lastReadChapter + 1}章"}",
                    fontSize = 11.sp,
                    color = theme.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
