package com.klic.mobile.app.feature.settings

import android.graphics.Bitmap
import android.net.Uri
import android.util.Patterns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.R
import com.klic.mobile.app.data.ImageUploads
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.components.AdjustMask
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.components.ImageAdjustSheet
import com.klic.mobile.app.ui.components.KlicSelectionSheet
import com.klic.mobile.app.ui.components.KlicSheetOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** §11.4: server-side username rules, mirrored for instant inline validation. */
private val USERNAME_REGEX = Regex("^[a-z0-9_.]{3,32}$")

private fun usernameValid(value: String): Boolean =
    USERNAME_REGEX.matches(value) && !value.startsWith(".") && !value.endsWith(".")

private fun linkValid(value: String): Boolean =
    value.length <= 200 &&
        (value.startsWith("http://") || value.startsWith("https://")) &&
        Patterns.WEB_URL.matcher(value).matches()

/**
 * Profile editor (§11.4/§11.5): photo (with pinch-zoom adjust step), display name,
 * @username with inline validation + server "Username is taken" errors, About
 * presets/custom, and up to five links.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(vm: KlicViewModel, onDone: () -> Unit) {
    val user by vm.currentUser.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var displayName by remember(user?.id) { mutableStateOf(user?.displayName ?: "") }
    var username by remember(user?.id) { mutableStateOf(user?.username ?: "") }
    var about by remember(user?.id) { mutableStateOf(user?.about) }
    var links by remember(user?.id) { mutableStateOf(user?.links ?: emptyList<String>()) }
    var croppedAvatar by remember { mutableStateOf<Bitmap?>(null) }
    var adjustUri by remember { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }
    var usernameError by remember { mutableStateOf<String?>(null) }

    var showAboutSheet by remember { mutableStateOf(false) }
    var showCustomAbout by remember { mutableStateOf(false) }
    // null = closed; -1 = adding a new link; >= 0 = editing links[index].
    var linkEditorIndex by remember { mutableStateOf<Int?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // §11.5: adjust step first — the crop feeds the existing upload flow.
        if (uri != null) adjustUri = uri
    }

    val aboutPresets = listOf(
        stringResource(R.string.about_preset_free),
        stringResource(R.string.about_preset_slow),
        stringResource(R.string.about_preset_traveling),
        stringResource(R.string.about_preset_at_work),
        stringResource(R.string.about_preset_busy),
        stringResource(R.string.about_preset_battery),
    )

    val usernameInvalidText = stringResource(R.string.profile_username_invalid)
    val trimmedUsername = username.trim()
    val usernameChanged = trimmedUsername != (user?.username ?: "")
    val usernameOk = !usernameChanged || usernameValid(trimmedUsername)

    fun save() {
        if (saving) return
        if (!usernameOk) {
            usernameError = usernameInvalidText
            return
        }
        saving = true
        scope.launch {
            val avatar = withContext(Dispatchers.IO) {
                croppedAvatar?.let { ImageUploads.encodeBitmap(it) }
            }
            val aboutChanged = (about ?: "") != (user?.about ?: "")
            val linksChanged = links != (user?.links ?: emptyList<String>())
            vm.saveProfile(
                displayName = displayName.trim(),
                username = trimmedUsername.takeIf { usernameChanged },
                setAbout = aboutChanged,
                about = about?.trim()?.takeIf { it.isNotBlank() },
                links = links.takeIf { linksChanged },
                avatarBytes = avatar?.bytes,
                contentType = avatar?.contentType,
                onDone = onDone,
                onError = { message ->
                    saving = false
                    if (message != null) {
                        // e.g. "Username is taken" — inline under the username row (§11.4).
                        usernameError = message
                    } else {
                        vm.error.value = context.getString(R.string.err_save_profile)
                    }
                },
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_edit_profile), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier.clickable { picker.launch("image/*") },
                contentAlignment = Alignment.BottomEnd,
            ) {
                val cropped = croppedAvatar
                if (cropped != null) {
                    Image(
                        bitmap = cropped.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(120.dp).clip(CircleShape),
                    )
                } else {
                    AvatarView(
                        url = user?.avatarUrl,
                        name = user?.displayName ?: "",
                        size = 120.dp,
                    )
                }
                Box(
                    modifier = Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PhotoCamera,
                        contentDescription = stringResource(R.string.profile_change_photo),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // ── Display name ──────────────────────────────────────────────────
            Spacer(Modifier.height(24.dp))
            FieldLabel(stringResource(R.string.auth_display_name))
            Spacer(Modifier.height(8.dp))
            CapsuleField(value = displayName, onChange = { displayName = it })

            // ── Username (§11.4) ──────────────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            FieldLabel(stringResource(R.string.auth_username))
            Spacer(Modifier.height(8.dp))
            CapsuleField(
                value = username,
                onChange = { raw ->
                    username = raw.lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '.' }.take(32)
                    usernameError = null
                },
                prefix = "@",
                isError = usernameError != null || !usernameOk,
            )
            val visibleUsernameError = usernameError ?: usernameInvalidText.takeIf { !usernameOk }
            if (visibleUsernameError != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    visibleUsernameError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ── About (§11.5) ─────────────────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            FieldLabel(stringResource(R.string.profile_about))
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { showAboutSheet = true }
                    .padding(horizontal = 18.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    about?.takeIf { it.isNotBlank() } ?: stringResource(R.string.profile_about_placeholder),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (about.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            // ── Links (§11.5) ─────────────────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            FieldLabel(stringResource(R.string.profile_links))
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp),
            ) {
                links.forEachIndexed { index, link ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { linkEditorIndex = index }
                            .padding(vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            link,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
                if (links.size < 5) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { linkEditorIndex = -1 }
                            .padding(vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.profile_add_link),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { save() },
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                enabled = !saving && displayName.trim().isNotEmpty() && trimmedUsername.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    if (saving) stringResource(R.string.common_saving) else stringResource(R.string.common_save),
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // §11.5: pinch-zoom/drag inside a circular mask, crop → square bitmap.
    adjustUri?.let { uri ->
        ImageAdjustSheet(
            uri = uri,
            mask = AdjustMask.CIRCLE,
            onDone = { bitmap ->
                croppedAvatar = bitmap
                adjustUri = null
            },
            onDismiss = { adjustUri = null },
        )
    }

    if (showAboutSheet) {
        KlicSelectionSheet(
            title = stringResource(R.string.profile_about),
            options = buildList {
                aboutPresets.forEach { add(KlicSheetOption(it, it)) }
                add(KlicSheetOption("__custom__", stringResource(R.string.about_custom)))
                if (!about.isNullOrBlank()) {
                    add(KlicSheetOption("__clear__", stringResource(R.string.about_clear), accent = true))
                }
            },
            selectedValue = about?.takeIf { it in aboutPresets },
            onSelect = { value ->
                when (value) {
                    "__custom__" -> { showAboutSheet = false; showCustomAbout = true }
                    "__clear__" -> { about = null; showAboutSheet = false }
                    else -> { about = value; showAboutSheet = false }
                }
            },
            onDismiss = { showAboutSheet = false },
        )
    }

    if (showCustomAbout) {
        TextEntrySheet(
            title = stringResource(R.string.about_custom_title),
            initial = about ?: "",
            placeholder = stringResource(R.string.profile_about_placeholder),
            maxLength = 140,
            validate = { true },
            onSave = { value ->
                about = value.trim().takeIf { it.isNotBlank() }
                showCustomAbout = false
            },
            onDismiss = { showCustomAbout = false },
        )
    }

    linkEditorIndex?.let { index ->
        val editing = index >= 0
        TextEntrySheet(
            title = if (editing) stringResource(R.string.profile_edit_link) else stringResource(R.string.profile_add_link),
            initial = if (editing) links[index] else "https://",
            placeholder = stringResource(R.string.profile_link_placeholder),
            maxLength = 200,
            validate = ::linkValid,
            errorText = stringResource(R.string.profile_link_invalid),
            onSave = { value ->
                links = if (editing) {
                    links.mapIndexed { i, old -> if (i == index) value else old }
                } else {
                    links + value
                }
                linkEditorIndex = null
            },
            onRemove = if (editing) ({
                links = links.filterIndexed { i, _ -> i != index }
                linkEditorIndex = null
            }) else null,
            onDismiss = { linkEditorIndex = null },
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CapsuleField(
    value: String,
    onChange: (String) -> Unit,
    prefix: String? = null,
    isError: Boolean = false,
) {
    TextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        prefix = prefix?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        isError = isError,
        // Capsule input, matching the Login-page fields (§9.8).
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            errorContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
    )
}

/** Small Klic sheet with one capsule text field — custom About and link add/edit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextEntrySheet(
    title: String,
    initial: String,
    placeholder: String,
    maxLength: Int,
    validate: (String) -> Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    errorText: String? = null,
    onRemove: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf(initial) }
    var showError by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
            )
            TextField(
                value = text,
                onValueChange = { text = it.take(maxLength); showError = false },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            if (showError && errorText != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    errorText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    val trimmed = text.trim()
                    if (validate(trimmed)) onSave(trimmed) else showError = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
            ) { Text(stringResource(R.string.common_save), Modifier.padding(vertical = 6.dp)) }
            if (onRemove != null) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onRemove,
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) { Text(stringResource(R.string.profile_remove_link), Modifier.padding(vertical = 6.dp)) }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text(stringResource(R.string.common_cancel), Modifier.padding(vertical = 6.dp)) }
        }
    }
}
