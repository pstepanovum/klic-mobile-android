package com.klic.mobile.app.feature.report

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.R
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.launch

/** What a report is about (§12.1). Exactly one target, or none for an app problem. */
sealed class ReportTarget {
    /** Report a user from their profile page or a group-member sheet. */
    data class User(val userId: String, val displayName: String, val username: String) : ReportTarget()

    /** Report a message from the long-press menu; sender info powers one-tap block. */
    data class Message(
        val messageId: String,
        val senderId: String?,
        val senderDisplayName: String?,
        val senderUsername: String?,
    ) : ReportTarget()

    /** Settings → "Report a problem" — no target. */
    object Problem : ReportTarget()
}

/** The server's ReportCategory enum with localized labels, in contract order. */
private val categories = listOf(
    "SPAM" to R.string.report_cat_spam,
    "HARASSMENT" to R.string.report_cat_harassment,
    "HATE_SPEECH" to R.string.report_cat_hate_speech,
    "VIOLENCE" to R.string.report_cat_violence,
    "SEXUAL_CONTENT" to R.string.report_cat_sexual_content,
    "CHILD_SAFETY" to R.string.report_cat_child_safety,
    "SCAM_FRAUD" to R.string.report_cat_scam_fraud,
    "IMPERSONATION" to R.string.report_cat_impersonation,
    "ILLEGAL_ACTIVITY" to R.string.report_cat_illegal_activity,
    "OTHER" to R.string.report_cat_other,
)

private enum class Step { CATEGORY, DETAILS, DONE }

/**
 * The Klic report sheet (§12.1): category list → optional details → submit →
 * confirmation offering one-tap "Block @user" for user/message reports.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    vm: KlicViewModel,
    target: ReportTarget,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.CATEGORY) }
    var category by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }

    val title = when (target) {
        is ReportTarget.User -> stringResource(R.string.report_user_title, target.displayName)
        is ReportTarget.Message -> stringResource(R.string.report_message_title)
        ReportTarget.Problem -> stringResource(R.string.report_problem_title)
    }

    fun submit() {
        val cat = category ?: return
        if (submitting) return
        submitting = true
        submitError = null
        scope.launch {
            val error = vm.submitReport(
                category = cat,
                targetUserId = (target as? ReportTarget.User)?.userId,
                messageId = (target as? ReportTarget.Message)?.messageId,
                details = details,
            )
            submitting = false
            if (error == null) step = Step.DONE else submitError = error
        }
    }

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

            when (step) {
                Step.CATEGORY -> {
                    Text(
                        stringResource(R.string.report_pick_reason),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                            .padding(horizontal = 18.dp),
                    ) {
                        categories.forEachIndexed { index, (value, labelRes) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { category = value; step = Step.DETAILS }
                                    .padding(vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(labelRes),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (index != categories.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    CapsuleButton(
                        text = stringResource(R.string.common_cancel),
                        muted = true,
                        onClick = onDismiss,
                    )
                }

                Step.DETAILS -> {
                    // Selected reason — tappable to go back and change it.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                            .clickable { step = Step.CATEGORY }
                            .padding(horizontal = 18.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            categories.firstOrNull { it.first == category }
                                ?.let { stringResource(it.second) } ?: "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            painter = painterResource(KlicIcons.check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = details,
                        onValueChange = { details = it.take(1000) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        placeholder = {
                            Text(
                                stringResource(R.string.report_details_placeholder),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    submitError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { submit() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = CircleShape,
                        enabled = !submitting,
                    ) {
                        if (submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(stringResource(R.string.report_submit), Modifier.padding(vertical = 6.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    CapsuleButton(
                        text = stringResource(R.string.common_cancel),
                        muted = true,
                        onClick = onDismiss,
                    )
                }

                Step.DONE -> {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(56.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(KlicIcons.check),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.report_submitted),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.report_submitted_sub),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    // One-tap block for user/message reports (existing blocks API).
                    val blockable: Triple<String, String, String>? = when (target) {
                        is ReportTarget.User -> Triple(target.userId, target.displayName, target.username)
                        is ReportTarget.Message ->
                            if (target.senderId != null) {
                                Triple(
                                    target.senderId,
                                    target.senderDisplayName ?: target.senderUsername ?: "",
                                    target.senderUsername ?: "",
                                )
                            } else null
                        ReportTarget.Problem -> null
                    }
                    blockable?.let { (userId, displayName, username) ->
                        Button(
                            onClick = {
                                vm.blockUser(userId, displayName)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Text(
                                stringResource(R.string.report_block_user, username.ifBlank { displayName }),
                                Modifier.padding(vertical = 6.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    CapsuleButton(
                        text = stringResource(R.string.common_done),
                        muted = true,
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun CapsuleButton(text: String, muted: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
        colors = if (muted) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else ButtonDefaults.buttonColors(),
    ) { Text(text, Modifier.padding(vertical = 6.dp)) }
}
