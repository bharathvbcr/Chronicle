package com.chronicle.app.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chronicle.app.ui.theme.MyApplicationTheme

/**
 * Required by Health Connect policy for ACTION_SHOW_PERMISSIONS_RATIONALE.
 * Explains that imported sleep/steps stay in the local Syncthing vault.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyApplicationTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Health data privacy") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                    )
                                }
                            },
                        )
                    },
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(padding)
                            .padding(24.dp),
                    ) {
                        Text(
                            "Chronicle can import sleep and steps from Health Connect.",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "\nImported data is written only to your local journal vault " +
                                "(health/yyyy/MM.json) on this device. It is never uploaded to " +
                                "Chronicle servers — Chronicle has none. Sync to your Mac happens " +
                                "only through Syncthing (or whatever folder sync you configured).\n\n" +
                                "You can revoke Health Connect access anytime in system settings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
