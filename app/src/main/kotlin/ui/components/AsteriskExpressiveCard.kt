// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import ui.theme.ExpressiveInteractionState
import ui.theme.ExpressiveShapeRole
import ui.theme.expressiveShape

@Composable
internal fun rememberExpressiveShape(
    role: ExpressiveShapeRole,
    @Suppress("UNUSED_PARAMETER") state: ExpressiveInteractionState,
): Shape = expressiveShape(role)

@Composable
internal fun AsteriskExpressiveCard(
    modifier: Modifier = Modifier,
    role: ExpressiveShapeRole = ExpressiveShapeRole.ContentCard,
    selected: Boolean = false,
    expanded: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    selectedContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val interactionState = when {
        !enabled -> ExpressiveInteractionState.Disabled
        expanded -> ExpressiveInteractionState.Expanded
        selected -> ExpressiveInteractionState.Selected
        else -> ExpressiveInteractionState.Rest
    }
    val shape = rememberExpressiveShape(role, interactionState)
    val cardModifier = if (onClick == null && onLongClick == null) modifier else modifier.heightIn(min = 48.dp)
    val colors = CardDefaults.cardColors(
        containerColor = if (selected) selectedContainerColor else containerColor,
    )

    if (onLongClick != null && onClick != null) {
        Card(
            modifier = cardModifier,
            shape = shape,
            colors = colors,
            border = border,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = ripple(),
                        enabled = enabled,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    ),
                content = content,
            )
        }
    } else if (onClick == null) {
        Card(
            modifier = cardModifier,
            shape = shape,
            colors = colors,
            border = border,
            content = content,
        )
    } else {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            enabled = enabled,
            shape = shape,
            colors = colors,
            border = border,
            interactionSource = interactionSource,
            content = content,
        )
    }
}
