package com.freelancehub.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun FreelanceHubBrandMark(modifier: Modifier = Modifier) {
    val ribbon = Brush.horizontalGradient(
        colors = listOf(Color(0xFF176BFF), Color(0xFF12D7F0))
    )
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .width(42.dp)
                .height(11.dp)
                .background(ribbon, RoundedCornerShape(topStart = 8.dp, bottomEnd = 6.dp))
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .width(11.dp)
                .height(42.dp)
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF176BFF), Color(0xFF12D7F0))),
                    RoundedCornerShape(topStart = 8.dp, bottomEnd = 6.dp)
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(y = 17.dp)
                .width(30.dp)
                .height(10.dp)
                .background(ribbon, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
        )
    }
}
