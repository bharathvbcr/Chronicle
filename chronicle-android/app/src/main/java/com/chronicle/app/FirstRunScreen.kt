package com.chronicle.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronicle.app.ui.theme.JournalSerif

@Composable
fun FirstRunScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val rememberedUri = remember {
        context.getSharedPreferences("chronicle_prefs", Context.MODE_PRIVATE)
            .getString("vault_uri", null)
            ?.let { Uri.parse(it) }
    }
    val openDirectoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.setFolderUri(context, uri)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Chronicle",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 40.sp,
                    fontFamily = JournalSerif,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                ),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (rememberedUri != null) {
                    "Folder access needs to be confirmed again.\nChoose the same Chronicle directory Syncthing shares with your Mac."
                } else {
                    "Your second brain lives in a folder.\nPick the Chronicle directory Syncthing shares with your Mac."
                },
                style = MaterialTheme.typography.bodyLarge.copy(
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                ),
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = { openDirectoryLauncher.launch(rememberedUri) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("pick_folder_button"),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    if (rememberedUri != null) "Confirm Chronicle folder" else "Choose Chronicle folder",
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Local-first. Journal stays on device and Syncthing. Optional cloud AI (Grok) is off until you opt in under Settings.",
                style = MaterialTheme.typography.labelMedium.copy(
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                ),
            )
        }
    }
}
