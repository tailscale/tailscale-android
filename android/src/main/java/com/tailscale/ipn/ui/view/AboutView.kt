package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.BuildConfig
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.Links

@Composable
fun AboutView() {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            verticalArrangement = Arrangement.spacedBy(
                space = 20.dp, alignment = Alignment.CenterVertically
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .safeContentPadding()
        ) {
            Image(
                modifier = Modifier
                    .width(100.dp)
                    .height(100.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black)
                    .padding(15.dp),
                painter = painterResource(id = R.drawable.ic_tile),
                contentDescription = stringResource(R.string.app_icon_content_description)
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(
                    space = 2.dp, alignment = Alignment.CenterVertically
                ), horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.about_view_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = MaterialTheme.typography.titleLarge.fontSize,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = BuildConfig.VERSION_NAME,
                    fontWeight = MaterialTheme.typography.bodyMedium.fontWeight,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(
                    space = 4.dp, alignment = Alignment.CenterVertically
                ), horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OpenURLButton(
                    stringResource(R.string.acknowledgements), Links.LICENSES_URL
                )
                OpenURLButton(
                    stringResource(R.string.privacy_policy), Links.PRIVACY_POLICY_URL
                )
                OpenURLButton(
                    stringResource(R.string.terms_of_service), Links.TERMS_URL
                )
            }

            Text(
                stringResource(R.string.about_view_footnotes),
                fontWeight = FontWeight.Normal,
                fontSize = MaterialTheme.typography.labelMedium.fontSize,
                color = MaterialTheme.colorScheme.tertiary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun OpenURLButton(title: String, url: String) {
    val handler = LocalUriHandler.current

    Button(
        onClick = { handler.openUri(url) },
        content = {
            Text(title)
        },
        colors = ButtonDefaults.buttonColors(
            contentColor = MaterialTheme.colorScheme.secondary,
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    )
}