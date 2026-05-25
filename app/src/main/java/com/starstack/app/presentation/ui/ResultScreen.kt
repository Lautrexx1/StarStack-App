package com.starstack.app.presentation.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

/**
 * Displays the result of a completed stacking operation.
 * Provides options to share, view, or return to the session.
 */
@Composable
fun ResultScreen(
    outputPath: String,
    sessionName: String,
    nightMode: Boolean = true,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val bgColor   = if (nightMode) Color(0xFF000000) else Color(0xFF0A0A0A)
    val textColor = if (nightMode) StarStackColors.NightRedText else StarStackColors.TextPrimary
    val cardBg    = if (nightMode) Color(0xFF0D0000) else Color(0xFF141414)

    val outputFile = File(outputPath)
    val fileExists = outputFile.exists()
    val fileSizeKb = if (fileExists) outputFile.length() / 1024 else 0L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor)
            }
            Text(
                text = "Result",
                color = textColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Spacer(Modifier.height(24.dp))

        // Success icon
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = StarStackColors.Success,
            modifier = Modifier.size(64.dp)
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Stacking Complete",
            color = textColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = sessionName,
            color = StarStackColors.TextSecondary,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(24.dp))

        // File info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                ResultInfoRow(
                    label = "Output file",
                    value = outputFile.name,
                    nightMode = nightMode
                )
                Divider(color = StarStackColors.ControlBorder, modifier = Modifier.padding(vertical = 8.dp))
                ResultInfoRow(
                    label = "File size",
                    value = if (fileSizeKb > 1024) "%.1f MB".format(fileSizeKb / 1024f) else "$fileSizeKb KB",
                    nightMode = nightMode
                )
                Divider(color = StarStackColors.ControlBorder, modifier = Modifier.padding(vertical = 8.dp))
                ResultInfoRow(
                    label = "Format",
                    value = outputFile.extension.uppercase(),
                    nightMode = nightMode
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Action buttons
        if (fileExists) {
            Button(
                onClick = {
                    try {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            outputFile
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/*"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share stacked image"))
                    } catch (e: Exception) {
                        // Handle error
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (nightMode) Color(0xFF880000) else StarStackColors.Accent
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share Result")
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    try {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            outputFile
                        )
                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "image/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(viewIntent)
                    } catch (e: Exception) {
                        // Handle error
                    }
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (nightMode) StarStackColors.NightRedText else StarStackColors.TextPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open in Gallery")
            }
        }

        Spacer(Modifier.height(12.dp))

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Back to Session",
                color = StarStackColors.TextSecondary
            )
        }
    }
}

@Composable
private fun ResultInfoRow(label: String, value: String, nightMode: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = StarStackColors.TextSecondary, fontSize = 12.sp)
        Text(
            value,
            color = if (nightMode) StarStackColors.NightRedText else StarStackColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
