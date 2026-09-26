package com.neoruaa.xhsdn.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neoruaa.xhsdn.R
import com.neoruaa.xhsdn.XHSApplication
import com.neoruaa.xhsdn.ui.remoteThumbnail
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun WebAccountCard(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { (context.applicationContext as XHSApplication).appContainer.webAccount }
    val account by repository.account.collectAsStateWithLifecycle()
    val avatar by produceState<android.graphics.Bitmap?>(null, account?.avatar) {
        value = null
        account?.avatar?.takeIf { it.isNotBlank() }?.let { value = remoteThumbnail(context, it) }
    }
    Card(modifier = modifier, insideMargin = PaddingValues(0.dp)) {
        ArrowPreference(
            title = account?.nickname?.takeIf { it.isNotBlank() } ?: stringResource(R.string.settings_xhs_account),
            summary = stringResource(if (account == null) R.string.settings_account_signed_out else R.string.settings_account_signed_in),
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            startAction = {
                Box(Modifier.padding(end = 16.dp).size(48.dp)
                    .clip(CircleShape).background(MiuixTheme.colorScheme.onBackgroundVariant), contentAlignment = Alignment.Center) {
                    Icon(MiuixIcons.Regular.Contacts, contentDescription = null,
                        tint = MiuixTheme.colorScheme.surface, modifier = Modifier.size(28.dp))
                    avatar?.let { Image(it.asImageBitmap(), contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp)) }
                }
            },
            onClick = onClick,
        )
    }
}
