package com.openscreenrecorder.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImage

class AboutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }

        val items = listOf(
            AboutItem(
                title = "Version",
                subtitle = "v$versionName",
                url = "",
                icon = Icon.Drawable(R.drawable.ic_screen_record)
            ),
            AboutItem(
                title = "Source Code",
                subtitle = "View project repository",
                url = "https://github.com/aniruddha-ga/OpenScreenRecorderApp",
                icon = Icon.Drawable(R.drawable.ic_github)
            ),
            AboutItem(
                title = "Aniruddha",
                subtitle = "Maintainer",
                url = "https://github.com/aniruddha-ga",
                icon = Icon.Url("https://github.com/aniruddha-ga.png")
            )
        )

        setContent {
            OpenScreenRecorderTheme {
                AboutScreen(
                    items = items,
                    onBackClick = { finish() },
                    onItemClick = { url ->
                        if (url.isNotEmpty()) {
                            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                        }
                    }
                )
            }
        }
    }

    data class AboutItem(
        val title: String,
        val subtitle: String,
        val url: String,
        val icon: Icon?
    )

    sealed class Icon {
        data class Drawable(val resId: Int) : Icon()
        data class Url(val value: String) : Icon()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    items: List<AboutActivity.AboutItem>,
    onBackClick: () -> Unit,
    onItemClick: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items) { item ->
                val clickableModifier = if (item.url.isNotEmpty()) {
                    Modifier
                        .fillMaxWidth()
                        .clickable { onItemClick(item.url) }
                } else {
                    Modifier.fillMaxWidth()
                }

                Card(
                    modifier = clickableModifier,
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (val icon = item.icon) {
                            is AboutActivity.Icon.Drawable -> {
                                Icon(
                                    painter = painterResource(id = icon.resId),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(MaterialTheme.shapes.small)
                                )
                            }
                            is AboutActivity.Icon.Url -> {
                                AsyncImage(
                                    model = icon.value,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(MaterialTheme.shapes.small),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            null -> {}
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = item.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
