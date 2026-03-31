package com.childfocus.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BgTop          = Color(0xFF0D1117)
private val BgBottom       = Color(0xFF161B22)
private val Accent         = Color(0xFF4F8EF7)
private val AccentOff      = Color(0xFF3A3F4B)
private val TextPrimary    = Color(0xFFFFFFFF)
private val TextSecondary  = Color(0xFF8B949E)

/**
 * Landing screen shown when the accessibility service is not yet enabled.
 *
 * @param isWaiting True while the user is inside Accessibility Settings —
 *                  shows a pulsing spinner instead of the enable button.
 * @param onTurnOn  Called when the user taps "Enable Protection".
 */
@Composable
fun LandingScreen(
    isWaiting: Boolean,
    onTurnOn: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            ShieldOrSpinner(isWaiting = isWaiting)

            Spacer(Modifier.height(36.dp))

            Text(
                text = if (isWaiting) "Waiting for activation…" else "You are unprotected",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = if (isWaiting)
                    "Enable ChildFocus in the Accessibility Settings page that just opened, then come back here."
                else
                    "ChildFocus is not monitoring YouTube.\nTap below to activate protection.",
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(Modifier.height(48.dp))

            if (isWaiting) {
                WaitingButton()
            } else {
                EnableButton(onClick = onTurnOn)
            }

            Spacer(Modifier.height(20.dp))

            if (!isWaiting) {
                Text(
                    text = "You'll be taken to Accessibility Settings to enable the service.",
                    fontSize = 12.sp,
                    color = TextSecondary.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ShieldOrSpinner(isWaiting: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(160.dp)
    ) {
        if (isWaiting) {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.85f,
                targetValue = 1.15f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(scale)
                    .background(Accent.copy(alpha = 0.15f), CircleShape)
            )
            CircularProgressIndicator(
                modifier = Modifier.size(72.dp),
                color = Accent,
                strokeWidth = 3.dp
            )
        } else {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(Color(0x33FF1744), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "🔓", fontSize = 44.sp)
            }
        }
    }
}

@Composable
private fun EnableButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent)
    ) {
        Text(
            text = "Enable Protection",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
private fun WaitingButton() {
    Button(
        onClick = { /* no-op while waiting */ },
        enabled = false,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = AccentOff,
            disabledContentColor = TextSecondary
        )
    ) {
        Text(
            text = "Waiting for Settings…",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
