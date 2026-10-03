package com.coderGtm.yantra.ui.settings.groups

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import com.coderGtm.yantra.ui.components.containers.SettingsGroup
import com.coderGtm.yantra.ui.components.settingsItem.ButtonSetting
import com.coderGtm.yantra.ui.components.settingsItem.ToggleSetting

/**
 * Ankush private controls, also available from the CLI (ankush-* commands).
 * Each row carries a one-line description of what it does.
 */
@Composable
fun AnkushGroup(
    aliasLock: Boolean,
    onAliasLockChange: (Boolean) -> Unit,
    extraPrivacy: Boolean,
    onExtraPrivacyChange: (Boolean) -> Unit,
    guard: Boolean,
    onGuardChange: (Boolean) -> Unit,
    offsetText: String,
    onOpenOffsetSetter: () -> Unit,
) {
    SettingsGroup(title = "Ankush (private)") {
        ToggleSetting(
            title = "Alias lock",
            description = "ON: aliases need the time code and old command names are off. Same as ankush-alias on/off.",
            checked = aliasLock,
            onCheckedChange = onAliasLockChange,
        )
        HorizontalDivider()
        ButtonSetting(
            title = "Time-code offset: $offsetText",
            onClick = onOpenOffsetSetter,
        )
        HorizontalDivider()
        ToggleSetting(
            title = "Extra privacy",
            description = "Blocks screenshots and the recent-apps preview. Same as ankush-privacy on/off.",
            checked = extraPrivacy,
            onCheckedChange = onExtraPrivacyChange,
        )
        HorizontalDivider()
        ToggleSetting(
            title = "Settings guard",
            description = "Bounces out of the Android Settings app while locked. Needs Accessibility. Same as ankush-guard on/off.",
            checked = guard,
            onCheckedChange = onGuardChange,
        )
    }
}
