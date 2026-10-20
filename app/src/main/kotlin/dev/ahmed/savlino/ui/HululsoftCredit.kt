package dev.ahmed.savlino.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Space

/** The studio's site and Facebook page; opened outside the app only on an explicit tap. */
internal const val HULULSOFT_SITE = "https://hululsoft.mr"
internal const val HULULSOFT_FACEBOOK = "https://www.facebook.com/hululsoft"

internal fun hululsoftFacebookIntent() = Intent(Intent.ACTION_VIEW, Uri.parse(HULULSOFT_FACEBOOK))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

/** "Made by Hululsoft". The company name is never translated and stays LTR inside Arabic text. */
@Composable
private fun madeBy() = stringResource(R.string.company_made_by, NezzelFormat.isolate(stringResource(R.string.company_name)))

/** Quiet signature under Home's privacy line: the studio mark and its name, one TalkBack item that opens the site. */
@Composable
internal fun MadeByHululsoft(modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val spoken = stringResource(R.string.company_open_site, stringResource(R.string.company_name))
    Row(modifier.heightIn(min = 48.dp).clickable(onClickLabel = spoken) {
        runCatching { uri.openUri(HULULSOFT_SITE) }.onFailure { showLinkFailure(context) }
    }
        .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        // The mark is shown as supplied: never recoloured, cropped or mirrored.
        Image(painterResource(R.drawable.hululsoft_mark), null, Modifier.size(16.dp))
        Text(madeBy(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** About block: who builds Nezzel, with its site behind an explicit link. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HululsoftAbout() {
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val site = stringResource(R.string.company_site)
    val spoken = stringResource(R.string.company_open_site, stringResource(R.string.company_name))
    val facebookSpoken = stringResource(R.string.company_open_facebook, stringResource(R.string.company_name))
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Image(painterResource(R.drawable.hululsoft_mark), null, Modifier.size(40.dp))
            Text(madeBy(), style = MaterialTheme.typography.titleSmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            TextButton(onClick = { runCatching { uri.openUri(HULULSOFT_SITE) }.onFailure { showLinkFailure(context) } }, contentPadding = PaddingValues(vertical = Space.s),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = spoken }) {
                Text(site, style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr))
            }
            TextButton(onClick = { runCatching { context.startActivity(hululsoftFacebookIntent()) }.onFailure { showLinkFailure(context) } }, contentPadding = PaddingValues(vertical = Space.s),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = facebookSpoken }) {
                Text(stringResource(R.string.company_facebook), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun showLinkFailure(context: android.content.Context) {
    android.widget.Toast.makeText(context, R.string.company_link_failed, android.widget.Toast.LENGTH_SHORT).show()
}
