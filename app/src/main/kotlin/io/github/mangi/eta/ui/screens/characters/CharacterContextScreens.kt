package io.github.mangi.eta.ui.screens.characters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPageDefaults
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
internal fun CharacterPersonaScreen(store: CharacterLibraryStore, onBack: () -> Unit) {
    val personaTitle = stringResource(R.string.character_persona_title)
    val personaHint = stringResource(R.string.character_persona_description_hint)
    val nameLabel = stringResource(R.string.character_persona_name_label)
    val identityLabel = stringResource(R.string.character_persona_identity_label)
    val saveLabel = stringResource(R.string.character_persona_save)
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(title = personaTitle, onBack = onBack) {
                CharacterPageMessage(personaHint)
                CharacterTextField(nameLabel, store.personaDraft.name, { store.updatePersona(name = it) }, !store.busy, singleLine = true)
                CharacterTextField(identityLabel, store.personaDraft.description, { store.updatePersona(description = it) }, !store.busy, minLines = 6)
                CouiButton(
                    text = saveLabel,
                    onClick = { store.savePersona(onBack) },
                    enabled = !store.busy,
                    role = CouiButtonRole.Primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CouiPageDefaults.horizontalMargin, vertical = 12.dp),
                )
                CouiPageBottomSpacer()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(title = personaTitle, onBack = onBack, modifier = Modifier.imePadding()) {
                item { CharacterPageMessage(personaHint) }
                item(key = "name") { CharacterTextField(nameLabel, store.personaDraft.name, { store.updatePersona(name = it) }, !store.busy, singleLine = true) }
                item(key = "persona") { CharacterTextField(identityLabel, store.personaDraft.description, { store.updatePersona(description = it) }, !store.busy, minLines = 6) }
                item(key = "save") {
                    TextButton(
                        text = saveLabel,
                        onClick = { store.savePersona(onBack) },
                        enabled = !store.busy,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun CharacterSystemPromptScreen(onBack: () -> Unit) {
    val promptTitle = stringResource(R.string.character_system_prompt_title)
    val promptHint = stringResource(R.string.character_system_prompt_hint)
    val promptLabel = stringResource(R.string.character_system_prompt_content_label)
    val saveLabel = stringResource(R.string.character_system_prompt_save)
    var draft by remember {
        mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_GLOBAL_SYSTEM_PROMPT))
    }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(title = promptTitle, onBack = onBack) {
                CharacterPageMessage(promptHint)
                CharacterTextField(promptLabel, draft, { draft = it }, true, minLines = 8)
                CouiButton(
                    text = saveLabel,
                    onClick = {
                        Prefs.localAgentPreferences()?.edit()
                            ?.putString(Prefs.Keys.AGENT_GLOBAL_SYSTEM_PROMPT, draft.trim())
                            ?.apply()
                        onBack()
                    },
                    role = CouiButtonRole.Primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CouiPageDefaults.horizontalMargin, vertical = 12.dp),
                )
                CouiPageBottomSpacer()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(title = promptTitle, onBack = onBack, modifier = Modifier.imePadding()) {
                item { CharacterPageMessage(promptHint) }
                item(key = "prompt") {
                    CharacterTextField(promptLabel, draft, { draft = it }, true, minLines = 8)
                }
                item(key = "save") {
                    TextButton(
                        text = saveLabel,
                        onClick = {
                            Prefs.localAgentPreferences()?.edit()
                                ?.putString(Prefs.Keys.AGENT_GLOBAL_SYSTEM_PROMPT, draft.trim())
                                ?.apply()
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun CharacterMemoryScreen(id: String, store: CharacterLibraryStore, onBack: () -> Unit) {
    val memoryTitle = stringResource(R.string.character_memory_title)
    val memoryHint = stringResource(R.string.character_memory_description_hint)
    val contentLabel = stringResource(R.string.character_memory_content_label)
    val reloadLabel = stringResource(R.string.character_memory_reload)
    val saveLabel = stringResource(R.string.character_memory_save)
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(title = memoryTitle, onBack = onBack) {
                CharacterPageMessage(memoryHint)
                CharacterTextField(contentLabel, store.memoryDraft, store::updateMemory, !store.busy, minLines = 10)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CouiPageDefaults.horizontalMargin, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CouiButton(
                        text = reloadLabel,
                        onClick = { store.loadMemory(id, force = true) },
                        enabled = !store.busy,
                        modifier = Modifier.weight(1f),
                    )
                    CouiButton(
                        text = saveLabel,
                        onClick = { store.saveMemory(id) },
                        enabled = !store.busy,
                        role = CouiButtonRole.Primary,
                        modifier = Modifier.weight(1f),
                    )
                }
                CouiPageBottomSpacer()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(title = memoryTitle, onBack = onBack, modifier = Modifier.imePadding()) {
                item { CharacterPageMessage(memoryHint) }
                item(key = "memory") {
                    CharacterTextField(contentLabel, store.memoryDraft, store::updateMemory, !store.busy, minLines = 10)
                }
                item(key = "actions") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            text = reloadLabel,
                            onClick = { store.loadMemory(id, force = true) },
                            enabled = !store.busy,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = saveLabel,
                            onClick = { store.saveMemory(id) },
                            enabled = !store.busy,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
        }
    }
}
