package com.guixing.glasstuning

import kotlinx.serialization.Serializable
import com.guixing.store.createAnyFlow
import java.util.UUID

/**
 * A named snapshot of several [GlassTarget]s' params at once - separate from [glassTuningStoreFlow]
 * (the single "currently in effect" value per target, live-edited by the tuning panel's sliders).
 * A preset is a saved COPY of that, applying it just overwrites the current values for whichever
 * targets it covers via the same [updateGlassParams]/[glassTuningStoreFlow] write path everything
 * else already uses - see docs/DECISIONS.md 2026-08-15 entry for why this sits on top of the
 * existing override mechanism instead of introducing a third storage concept.
 */
@Serializable
data class GlassPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    // GlassTarget.name -> GlassParams, only for whichever targets this preset's caller cares
    // about (teleprompter's two today) - multiple targets sharing identical params still get one
    // entry each here (no dedup), since GlassTarget.name is the map key and the caller always
    // wants "one value per target back", not a "was it the same as some other target" question.
    val paramsByTarget: Map<String, GlassParams>,
)

@Serializable
data class GlassPresetStore(
    val presets: List<GlassPreset> = emptyList(),
)

/** Only ever holds user-created presets - the built-in preset row is never written here (see
 * [builtInPreset]), so it can't be deleted/renamed by construction, not by a UI-level guard that
 * could be bypassed or forgotten somewhere. */
val glassPresetStoreFlow = createAnyFlow(
    key = "glass_preset",
    default = { GlassPresetStore() },
)

/** Snapshots [targets]' CURRENTLY EFFECTIVE values from [store] (the caller's own
 * `glassTuningStoreFlow.collectAsState()` result, NOT a fresh `glassTuningStoreFlow.value` read
 * done here - see this function's own real-bug history below), not [defaultGlassParamsFor]
 * (2026-08-15, corrected - see docs/DECISIONS.md's "再次订正" entry: applying this row used to
 * silently reset whatever you'd just adjusted back to factory defaults, which is what "恢复默认"
 * one section up already does - real user feedback called the duplication out on-device).
 *
 * [store] MUST come from the caller's own `collectAsState()`, not a bare `glassTuningStoreFlow
 * .value` read inside this function (2026-08-15, second real bug on the same feature, this one
 * caught via the user's own screen recording): a plain `.value` read on a StateFlow is invisible
 * to Compose's snapshot system - it doesn't subscribe anything to it. `PresetSection` itself
 * never collected `glassTuningStoreFlow`, so nothing forced it to re-run this function after a
 * slider drag; it kept computing this row from whatever `glassTuningStoreFlow.value` happened to
 * be the last time `PresetSection` recomposed for some OTHER reason, which read as "应用" jumping
 * back to a stale/default-ish snapshot no matter how the sliders had just been dragged. */
fun builtInPreset(store: GlassTuningStore, targets: List<GlassTarget>): GlassPreset = GlassPreset(
    id = BUILT_IN_PRESET_ID,
    name = "默认",
    paramsByTarget = targets.associate { it.name to store.paramsFor(it) },
)

const val BUILT_IN_PRESET_ID = "builtin_default"

/** All presets for [targets] in display order: the synthesized built-in first, then user-created
 * ones as saved. [store] - see [builtInPreset]'s doc comment for why this must be the caller's
 * own `collectAsState()` result.
 *
 * Filtered to presets whose own `paramsByTarget` key set exactly matches [targets] (2026-08-16,
 * real gap exposed once a second restricted target-set - GeneralGlassTargets - existed alongside
 * TeleprompterGlassTargets): [glassPresetStoreFlow] is one flat list shared by every restricted
 * entry point, not partitioned by which screen created a preset. Without this filter, a preset
 * saved from teleprompter's tuning panel would also show up in the unrelated general (launcher/
 * appearance) tuning screen's list - and silently no-op on "应用" there, since its param map has
 * no LauncherCard/SettingsPanel keys for `applyGlassPreset`'s `?: return@forEach` to find. Every
 * preset's key set is always exactly the target list it was saved under (see [saveGlassPreset]),
 * so exact key-set equality is a correct, tag-free partition - no separate "which screen" field
 * needed. */
fun GlassPresetStore.allPresetsFor(store: GlassTuningStore, targets: List<GlassTarget>): List<GlassPreset> {
    val targetNames = targets.map { it.name }.toSet()
    return listOf(builtInPreset(store, targets)) + presets.filter { it.paramsByTarget.keys == targetNames }
}

/** Snapshots [targets]' CURRENTLY EFFECTIVE values (glassTuningStoreFlow.paramsFor - what
 * liveGlassParamsFor actually reads, i.e. real screens' real current look), not whatever a tuning
 * panel slider might be mid-drag on before the user commits it - there's no separate "committed"
 * vs "preview" state in this store, every slider edit already writes straight through. */
fun saveGlassPreset(name: String, targets: List<GlassTarget>) {
    val current = glassTuningStoreFlow.value
    val snapshot = targets.associate { it.name to current.paramsFor(it) }
    val preset = GlassPreset(name = name, paramsByTarget = snapshot)
    glassPresetStoreFlow.value = glassPresetStoreFlow.value.copy(
        presets = glassPresetStoreFlow.value.presets + preset,
    )
}

/** Writes [preset]'s snapshot back into glassTuningStoreFlow for every target it covers - the
 * exact same store liveGlassParamsFor reads, so every real screen using one of these targets
 * updates immediately, no restart/re-entry needed. Deliberately NOT special-cased for the
 * built-in preset (2026-08-15) - an earlier version called resetGlassParams(target) for it
 * instead, which is a different write (removes the override key entirely, falls through to
 * defaultGlassParamsFor on every future read) from what every other preset's "应用" does (writes
 * an explicit stored override) - two different mechanisms producing a visually identical result
 * was confusing/redundant with the per-target "恢复默认" button one section up. Now "应用" always
 * means the same thing regardless of which preset: write this preset's own paramsByTarget. */
fun applyGlassPreset(preset: GlassPreset, targets: List<GlassTarget>) {
    var store = glassTuningStoreFlow.value
    targets.forEach { target ->
        val params = preset.paramsByTarget[target.name] ?: return@forEach
        store = store.withParams(target, params)
    }
    glassTuningStoreFlow.value = store
}

fun deleteGlassPreset(id: String) {
    if (id == BUILT_IN_PRESET_ID) return
    glassPresetStoreFlow.value = glassPresetStoreFlow.value.copy(
        presets = glassPresetStoreFlow.value.presets.filterNot { it.id == id },
    )
}

fun renameGlassPreset(id: String, newName: String) {
    if (id == BUILT_IN_PRESET_ID) return
    glassPresetStoreFlow.value = glassPresetStoreFlow.value.copy(
        presets = glassPresetStoreFlow.value.presets.map {
            if (it.id == id) it.copy(name = newName) else it
        },
    )
}
