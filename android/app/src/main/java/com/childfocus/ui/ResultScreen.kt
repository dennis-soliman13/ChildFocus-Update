package com.childfocus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ResultScreen(
    videoId: String,
    label: String,
    score: Float,
    cached: Boolean,
    onBack: () -> Unit
) {
    val (bgColor, accentColor, emoji) = when (label) {
        "Overstimulating" -> Triple(Color(0xFF3E1A1A), Color(0xFFEF9A9A), "⛔")
        "Educational"     -> Triple(Color(0xFF1B3A2A), Color(0xFF81C784), "📚")
        else              -> Triple(Color(0xFF1E3050), Color(0xFF90CAF9), "✅")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1B2A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "$emoji $label",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = bgColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ResultRow("Video ID", videoId.take(16) + if (videoId.length > 16) "..." else "")
                    ResultRow("OIR Score", "%.4f".format(score))
                    ResultRow("Source", if (cached) "Cache (instant)" else "Live classification")
                }
            }

            // Score bar
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Overstimulation Index",
                    color = Color(0xFF90CAF9),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { score.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp),
                    color = when {
                        score >= 0.75f -> Color(0xFFEF9A9A)
                        score <= 0.35f -> Color(0xFF81C784)
                        else           -> Color(0xFFFFD54F)
                    },
                    trackColor = Color(0xFF1E3A5F)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Safe", color = Color(0xFF81C784), fontSize = 11.sp)
                    Text("Neutral", color = Color(0xFFFFD54F), fontSize = 11.sp)
                    Text("Block", color = Color(0xFFEF9A9A), fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4FC3F7))
            ) {
                Text("Back", color = Color(0xFF0D1B2A), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF78909C), fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false))
    }
}
