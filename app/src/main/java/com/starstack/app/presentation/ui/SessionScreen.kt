package com.starstack.app.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starstack.app.data.model.CaptureType
import com.starstack.app.data.model.Session
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SessionScreen(
    sessions: List<Session>,
    onCreateSession: (String) -> Unit,
    onSelectSession: (Session) -> Unit,
    onDeleteSession: (Session) -> Unit,
    nightMode: Boolean = true
) {
    val bgColor   = if (nightMode) Color(0xFF000000) else Color(0xFF0A0A0A)
    val textColor = if (nightMode) StarStackColors.NightRedText else StarStackColors.TextPrimary

    var showCreateDialog by remember { mutableStateOf(false) }
    var newSessionName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Sessions",
                color = textColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { showCreateDialog = true }) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "New Session",
                    tint = if (nightMode) StarStackColors.NightRedText else StarStackColors.Accent
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.NightlightRound,
                        contentDescription = null,
                        tint = StarStackColors.TextSecondary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No sessions yet",
                        color = StarStackColors.TextSecondary,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap + to create your first astrophotography session",
                        color = StarStackColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions) { session ->
                    SessionCard(
                        session = session,
                        nightMode = nightMode,
                        onClick = { onSelectSession(session) },
                        onDelete = { onDeleteSession(session) }
                    )
                }
            }
        }
    }

    // Create Session Dialog
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = if (nightMode) Color(0xFF1A0000) else Color(0xFF1A1A1A),
            title = {
                Text(
                    "New Session",
                    color = if (nightMode) StarStackColors.NightRedText else StarStackColors.TextPrimary
                )
            },
            text = {
                OutlinedTextField(
                    value = newSessionName,
                    onValueChange = { newSessionName = it },
                    label = {
                        Text(
                            "Target name (e.g. Milky Way)",
                            color = StarStackColors.TextSecondary,
                            fontSize = 12.sp
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = if (nightMode) StarStackColors.NightRedText else StarStackColors.TextPrimary,
                        unfocusedTextColor = StarStackColors.TextSecondary,
                        focusedBorderColor = if (nightMode) StarStackColors.NightRedText else StarStackColors.Accent,
                        unfocusedBorderColor = StarStackColors.ControlBorder
                    ),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newSessionName.isNotBlank()) {
                            onCreateSession(newSessionName.trim())
                            newSessionName = ""
                            showCreateDialog = false
                        }
                    }
                ) {
                    Text(
                        "Create",
                        color = if (nightMode) StarStackColors.NightRedText else StarStackColors.Accent
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = StarStackColors.TextSecondary)
                }
            }
        )
    }
}

@Composable
fun SessionCard(
    session: Session,
    nightMode: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val cardBg    = if (nightMode) Color(0xFF0D0000) else Color(0xFF141414)
    val textColor = if (nightMode) StarStackColors.NightRedText else StarStackColors.TextPrimary
    val borderColor = if (nightMode) Color(0xFF330000) else StarStackColors.ControlBorder

    val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    val dateStr = dateFormat.format(Date(session.timestamp))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.NightlightRound,
                contentDescription = null,
                tint = if (nightMode) StarStackColors.NightRedText else StarStackColors.Accent,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.targetName,
                    color = textColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = dateStr,
                    color = StarStackColors.TextSecondary,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrameCountChip("${session.frameCount}L", CaptureType.FRAMES, nightMode)
                    FrameCountChip("${session.darkCount}D", CaptureType.DARKS, nightMode)
                    FrameCountChip("${session.flatCount}F", CaptureType.FLATS, nightMode)
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Delete",
                    tint = StarStackColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun FrameCountChip(label: String, type: CaptureType, nightMode: Boolean) {
    val color = when (type) {
        CaptureType.FRAMES -> if (nightMode) StarStackColors.NightRedText else StarStackColors.Accent
        CaptureType.DARKS  -> Color(0xFF757575)
        CaptureType.FLATS  -> Color(0xFFAFB42B)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(text = label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}
