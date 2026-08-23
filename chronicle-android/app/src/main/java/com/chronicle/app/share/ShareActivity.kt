package com.chronicle.app.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import coil.compose.AsyncImage
import com.chronicle.app.BiometricHelper
import com.chronicle.app.Entry
import com.chronicle.app.VaultRepository
import com.chronicle.app.generateEntryId
import com.chronicle.app.hasPersistedPermission
import com.chronicle.app.ui.theme.MyApplicationTheme
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share-sheet target: text and images (HEIC→JPEG via processAndSaveImage) become entries.
 * When biometric lock is enabled in prefs, requires success before any vault write.
 */
class ShareActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedText = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            else -> ""
        }
        val imageUris = mutableListOf<Uri>()
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                uri?.let { imageUris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                list?.let { imageUris.addAll(it) }
            }
        }

        val prefs = getSharedPreferences("chronicle_prefs", MODE_PRIVATE)
        val vault = prefs.getString("vault_uri", null)
        if (!hasPersistedPermission(this, vault)) {
            Toast.makeText(this, "Open Chronicle and pick your folder first.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val biometricEnabled = prefs.getBoolean("biometric_enabled", false)

        setContent {
            MyApplicationTheme {
                var unlocked by remember { mutableStateOf(!biometricEnabled) }
                var text by remember { mutableStateOf(sharedText) }
                var saving by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                LaunchedEffect(biometricEnabled) {
                    if (biometricEnabled && !unlocked) {
                        BiometricHelper.showBiometricPrompt(
                            activity = this@ShareActivity,
                            onSuccess = { unlocked = true },
                            onError = { msg ->
                                Toast.makeText(this@ShareActivity, msg, Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (biometricEnabled && !unlocked) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "Unlock to save to Chronicle",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    BiometricHelper.showBiometricPrompt(
                                        activity = this@ShareActivity,
                                        onSuccess = { unlocked = true },
                                        onError = { msg ->
                                            Toast.makeText(
                                                this@ShareActivity,
                                                msg,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        },
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Unlock with Biometrics")
                            }
                        }
                        return@Surface
                    }

                    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                        Text("Save to Chronicle", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            placeholder = { Text("Add a note…") },
                        )
                        if (imageUris.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(imageUris, key = { it.toString() }) { uri ->
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = null,
                                        modifier = Modifier.height(72.dp),
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            enabled = !saving && (text.isNotBlank() || imageUris.isNotEmpty()),
                            onClick = {
                                if (biometricEnabled && !unlocked) {
                                    Toast.makeText(
                                        this@ShareActivity,
                                        "Unlock required",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    return@Button
                                }
                                saving = true
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        try {
                                            val treeUri = Uri.parse(vault)
                                            val repo = VaultRepository(this@ShareActivity, treeUri)
                                            val now = ZonedDateTime.now()
                                            val id = generateEntryId(now, exists = { repo.entryFileExists(it) })
                                            // Seal like every other capture path: when E2EE is
                                            // enabled+unlocked the text never touches disk in the
                                            // clear; when locked, save plaintext (capture wins)
                                            // but LanOutboxWorker skips mirroring it.
                                            val sealedBlob =
                                                com.chronicle.app.e2ee.E2eeManager.sealText(text)
                                            val entry = Entry(
                                                id = id,
                                                ts = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                                                type = "log",
                                                text = if (sealedBlob != null) "" else text,
                                                textEnc = sealedBlob,
                                                tags = emptyList(),
                                                processed = false,
                                            )
                                            val saved = repo.saveEntry(entry, imageUris)
                                            if (saved != null) {
                                                com.chronicle.app.net.LanOutboxWorker.enqueueEntry(
                                                    this@ShareActivity,
                                                    org.json.JSONObject(com.chronicle.app.serializeEntry(saved)),
                                                )
                                            }
                                            saved != null
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            false
                                        }
                                    }
                                    Toast.makeText(
                                        this@ShareActivity,
                                        if (ok) "Saved" else "Save failed",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    finish()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (saving) "Saving…" else "Save")
                        }
                    }
                }
            }
        }
    }
}
