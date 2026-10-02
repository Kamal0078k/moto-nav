package dev.motonav.simulator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun MotoCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Moto.colors.surface)
            .border(1.dp, if (highlighted) Moto.colors.accent.copy(alpha = 0.55f) else Moto.colors.outline, shape)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Moto.colors.textFaint) {
    Text(text.uppercase(), modifier = modifier, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
fun Pill(
    text: String,
    color: Color,
    background: Color = color.copy(alpha = 0.14f),
    icon: ImageVector? = null,
    dot: Boolean = false,
) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    container: Color = Moto.colors.accent,
    content: Color = Moto.colors.onAccent,
    height: Dp = 56.dp,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(height),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = Moto.colors.surfaceRaised,
            disabledContentColor = Moto.colors.textFaint,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    tint: Color = Moto.colors.text,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Moto.colors.outlineStrong),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Moto.colors.surfaceRaised,
            contentColor = tint,
            disabledContentColor = Moto.colors.textFaint,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Round icon control with a visible caption, so meaning never relies on the glyph alone. */
@Composable
fun RoundAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    enabled: Boolean = true,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(if (emphasized) 64.dp else 52.dp)
                .clip(CircleShape)
                .background(if (emphasized) Moto.colors.accent else Moto.colors.surfaceRaised)
                .border(1.dp, if (emphasized) Color.Transparent else Moto.colors.outlineStrong, CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (emphasized) Moto.colors.onAccent else if (enabled) Moto.colors.text else Moto.colors.textFaint,
                modifier = Modifier.size(if (emphasized) 30.dp else 24.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Moto.colors.textMuted)
    }
}

@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier, valueColor: Color = Moto.colors.text) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Moto.colors.textMuted)
    }
}

@Composable
fun IconTile(icon: ImageVector, tint: Color, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun ListRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    tint: Color = Moto.colors.textMuted,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = { },
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Moto.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Moto.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

@Composable
fun Meter(label: String, used: Int, limit: Int, modifier: Modifier = Modifier) {
    val fraction = (used.toFloat() / limit).coerceIn(0f, 1f)
    val color = when {
        fraction >= 0.9f -> Moto.colors.danger
        fraction >= 0.75f -> Moto.colors.warning
        else -> Moto.colors.success
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Moto.colors.textMuted, modifier = Modifier.weight(1f))
            Text("$used / $limit", style = NumericStyle, color = Moto.colors.text)
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = color,
            trackColor = Moto.colors.surfaceRaised,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = { },
        )
    }
}

@Composable
fun MotoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    numeric: Boolean = false,
    singleLine: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    supporting: String? = null,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = Moto.colors.textFaint) } },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        isError = isError,
        trailingIcon = trailing,
        supportingText = supporting?.let { { Text(it) } },
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Moto.colors.accent,
            unfocusedBorderColor = Moto.colors.outlineStrong,
            focusedContainerColor = Moto.colors.surfaceSunken,
            unfocusedContainerColor = Moto.colors.surfaceSunken,
            focusedLabelColor = Moto.colors.accent,
            unfocusedLabelColor = Moto.colors.textMuted,
            cursorColor = Moto.colors.accent,
        ),
    )
}
