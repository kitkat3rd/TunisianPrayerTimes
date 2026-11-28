package com.tunisianprayertimes.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.CardBg
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextMuted

internal enum class FirstRunArt(@DrawableRes val image: Int) {
    Place(R.drawable.first_run_place_art),
    Silence(R.drawable.first_run_silence_art),
    Quran(R.drawable.first_run_quran_art),
}

@Composable
internal fun FirstRunHeader(
    title: String,
    description: String?,
    art: FirstRunArt,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    Column(modifier.fillMaxWidth()) {
        // Reserve the first viewport for the choices in landscape and when text is enlarged.
        if (windowHeight >= 460.dp * density.fontScale.coerceAtLeast(1f)) {
            Image(
                painterResource(art.image), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(3f).clip(RoundedCornerShape(18.dp)),
            )
            Spacer(Modifier.height(16.dp))
        }
        Text(title, titleModifier.semantics { heading() }, color = GreenPrimaryDark,
            fontWeight = FontWeight.Bold, fontSize = 29.sp, lineHeight = 39.sp)
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(description, color = TextMuted, fontSize = 15.sp, lineHeight = 23.sp)
        }
    }
}

@Composable
internal fun FirstRunLibraryItem(
    title: String,
    subtitle: String?,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().background(CardBg).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (LocalDensity.current.fontScale < 1.5f) {
            Box(Modifier.size(38.dp).background(Color(0xFFF6F0DE), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(21.dp), tint = Color(0xFF766130))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, Modifier.semantics { heading() }, color = GreenPrimaryDark,
                fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = TextMuted, fontSize = 14.sp, lineHeight = 21.sp)
            }
            content()
        }
    }
}

@Composable
internal fun FirstRunLibraryAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick, modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE9F3ED), contentColor = GreenPrimaryDark),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) { Text(label, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium) }
}
