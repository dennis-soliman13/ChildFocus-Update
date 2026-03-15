package com.childfocus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LandingScreen(onTurnOn: () -> Unit) {

    val bgGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF0D1B2A), Color(0xFF1B2838))
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgGradient),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {

            // Logo / title
            Text(
                text = "ChildFocus",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4FC3F7),
                letterSpacing = 2.sp
            )

            Text(
                text = "A CHILD'S FOCUS, IN SAFE HANDS.",
                fontSize = 12.sp,
                color = Color(0xFF90CAF9),
                letterSpacing = 3.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Feature pills
            listOf(
                "🎬 AI-Powered Overstimulation Detection",
                "🌐 Website Blocking",
                "⏱️ Screen-Time Control",
                "🔒 Content Restrictions"
            ).forEach { feature ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF1E3A5F),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = feature,
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // CTA Button
            Button(
                onClick = onTurnOn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4FC3F7)
                )
            ) {
                Text(
                    text = "TURN ON SAFETY MODE",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF0D1B2A),
                    letterSpacing = 1.sp
                )
            }

            Text(
                text = "We're here to support you\nin protecting children",
                color = Color(0xFF78909C),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
