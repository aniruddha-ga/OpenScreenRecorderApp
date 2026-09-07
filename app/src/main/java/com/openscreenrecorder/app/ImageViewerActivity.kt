package com.openscreenrecorder.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage

class ImageViewerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EXTRA_IMAGE_TITLE = "extra_image_title"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uriStr = intent.getStringExtra(EXTRA_IMAGE_URI)
        val imageTitle = intent.getStringExtra(EXTRA_IMAGE_TITLE) ?: "Screenshot"

        if (uriStr.isNullOrEmpty()) {
            Toast.makeText(this, "Invalid image path", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val imageUri = uriStr.toUri()

        setContent {
            OpenScreenRecorderTheme {
                ImageViewerScreen(
                    imageUri = imageUri,
                    title = imageTitle,
                    onBackClick = { finish() },
                    onDeleteImage = {
                        deleteImage(imageUri)
                    }
                )
            }
        }
    }

    private fun deleteImage(uri: Uri) {
        var deleted = false
        try {
            val isDocument = DocumentsContract.isDocumentUri(this, uri) ||
                    (uri.scheme == "content" && uri.authority?.contains("documents") == true)

            if (isDocument) {
                try {
                    deleted = DocumentsContract.deleteDocument(contentResolver, uri)
                } catch (_: Exception) {}

                if (!deleted) {
                    try {
                        deleted = DocumentFile.fromSingleUri(this, uri)?.delete() == true
                    } catch (_: Exception) {}
                }
            } else {
                val rows = contentResolver.delete(uri, null, null)
                deleted = rows > 0
            }
        } catch (_: Exception) {}

        if (deleted) {
            Toast.makeText(this, "Screenshot deleted", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        } else {
            Toast.makeText(this, "Failed to delete screenshot", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    imageUri: Uri,
    title: String,
    onBackClick: () -> Unit,
    onDeleteImage: () -> Unit
) {
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val editIntent = Intent(context, ScreenshotEditorActivity::class.java).apply {
                            putExtra(ScreenshotEditorActivity.EXTRA_IMAGE_URI, imageUri.toString())
                            putExtra(ScreenshotEditorActivity.EXTRA_MODE, ScreenshotEditorActivity.MODE_EDIT)
                        }
                        context.startActivity(editIntent)
                    }) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit Screenshot")
                    }
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, imageUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share Screenshot"))
                    }) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = imageUri,
                contentDescription = "Screenshot Preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Screenshot") },
            text = { Text("Are you sure you want to delete this screenshot?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDeleteImage()
                }) {
                    Text("Delete", color = Color(0xFFFF2222), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            modifier = Modifier.border(
                BorderStroke(1.dp, if (isSystemInDarkTheme()) Color(0xFF2C2C2C) else Color(0xFFE0E0E0)),
                RoundedCornerShape(24.dp)
            ),
            shape = RoundedCornerShape(24.dp),
            containerColor = if (isSystemInDarkTheme()) Color.Black else Color.White
        )
    }
}
