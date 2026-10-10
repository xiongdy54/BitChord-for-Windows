package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.music.bitchord.auth.AccountController
import com.music.bitchord.auth.WebSessionMode
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.add_account
import com.music.bitchord.desktop.resources.brand_account
import com.music.bitchord.desktop.resources.choose_profile
import com.music.bitchord.desktop.resources.listen_as
import com.music.bitchord.desktop.resources.remove_account
import com.music.bitchord.desktop.resources.sign_out
import org.jetbrains.compose.resources.stringResource

/**
 * Which identity to listen as — the desktop's convergence of the Android
 * `AccountChannelDialog` and its accounts page: one radio list built from the
 * registry's flattened profiles (every Google login's personal channel plus
 * its brand channels, in stable selection order), with every identity action
 * gathered under it.
 *
 * The selected row is the app's own choice, not YouTube's — the tick answers
 * "which one is this app acting as", which before a choice is made is decided
 * by the shell rather than by the listener. A channel YouTube's own switcher
 * reports as active is therefore never ticked merely for being active.
 *
 * The rows under the list are the two ways the registry grows or shrinks:
 *〔add account〕opens a fresh Google login (SIGN_IN),〔choose a profile〕opens
 * YouTube Music itself so the login's own Accounts switcher can be used — the
 * route that cannot be wrong about what exists (SWITCH_CHANNEL) — and the
 * removal rows retire what is there. The channel detection that keeps the
 * list current runs on open, so a brand channel created on the web since the
 * last capture appears without a new login.
 */
@Composable
fun AccountDialog(
    /** The session registry and its selection — this dialog is only a surface. */
    controller: AccountController,
    /** Opens the login window; [WebSessionMode] says which of the two routes. */
    onOpenLogin: (WebSessionMode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accounts by controller.accounts.collectAsState()
    val activeAccountId by controller.activeAccountId.collectAsState()
    val activeProfileId by controller.activeProfileId.collectAsState()
    val brandLabel = stringResource(Res.string.brand_account)

    // The API-detected half of the list: channels the login has that no page
    // capture has seen yet. The merge lands in the registry, which the rows
    // below read — one refresh per open is enough.
    LaunchedEffect(Unit) { controller.loadChannels(force = false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier.widthIn(max = 420.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(
                    text = stringResource(Res.string.listen_as),
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                accounts.forEach { account ->
                    account.profiles.forEach { profile ->
                        ProfileRow(
                            title = profile.name,
                            // The handle when there is one; otherwise what kind
                            // of channel it is, which is the part a listener
                            // with two of them is choosing by.
                            subtitle = profile.handle.ifBlank {
                                if (profile.isBrandAccount) brandLabel else account.email
                            },
                            avatarUrl = profile.avatar,
                            selected = account.accountId == activeAccountId &&
                                profile.profileId == activeProfileId,
                            onSelect = {
                                controller.selectProfile(account.accountId, profile.profileId)
                                onDismiss()
                            },
                        )
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                ActionRow(stringResource(Res.string.choose_profile)) {
                    onOpenLogin(WebSessionMode.SWITCH_CHANNEL)
                    onDismiss()
                }
                ActionRow(stringResource(Res.string.add_account)) {
                    onOpenLogin(WebSessionMode.SIGN_IN)
                    onDismiss()
                }
                // A registry of one has nothing to fall back to — removal and
                // sign-out are the same act, and the sign-out row says so.
                if (accounts.size > 1) {
                    ActionRow(stringResource(Res.string.remove_account), destructive = true) {
                        activeAccountId?.let(controller::removeAccount)
                        onDismiss()
                    }
                }
                ActionRow(stringResource(Res.string.sign_out), destructive = true) {
                    controller.signOut()
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    title: String,
    subtitle: String,
    avatarUrl: String?,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        RadioButton(selected = selected, onClick = onSelect)
    }
}

@Composable
private fun ActionRow(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (destructive) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    )
}
