/*
 * 提词器的 ViewModel：文档/章节的增删改、结构操作（合并、拆分、段落升级为章节）、撤销。
 *
 * 所有写操作最终都落到 `updateTeleprompterLibrary { }`（TeleprompterStore.kt），它整体替换整个
 * StateFlow。结构操作会 bump 章节的 `structureVersion`，编辑页据此重新水合对应章节的富文本状态。
 *
 * 注意时序陷阱：防抖自动保存可能在一次合并/拆分**之后**才触发，把合并前的旧内容写回去。编辑页
 * 那边靠"`LaunchedEffect` 被新 key 取消重启"隐式丢弃了在途的旧防抖，改动这一块时别破坏这个性质。
 */
package li.songe.gkd.teleprompter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import li.songe.gkd.glassui.GlassBackgroundConfig
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.json
import li.songe.gkd.util.storeFolder

private const val TAG = "Prompter"
private const val HTML_LENGTH_WARN_THRESHOLD = 15000

// Observation-only instrumentation for the "降级为正文之后内容消失" investigation - every log
// line includes char count + first 50 chars so the full data lifecycle can be read straight out
// of logcat without guessing. Not a fix; see TeleprompterVm.mergeChapterIntoPreviousChapter and
// TeleprompterEditorScreen's ChapterItem for where each numbered step logs.
private fun preview(s: String) = "len=${s.length}, head=\"${s.take(50)}\""

class TeleprompterVm : ViewModel() {
    val library = teleprompterLibraryFlow

    private val _currentDocId = MutableStateFlow<String?>(null)
    val currentDocId: StateFlow<String?> = _currentDocId.asStateFlow()

    private val _focusedChapterId = MutableStateFlow<String?>(null)
    val focusedChapterId: StateFlow<String?> = _focusedChapterId.asStateFlow()

    // Single-step undo for the two structural chapter-restructuring ops below (Fix B) - "至少
    // 一次撤销" is the bar, not a full undo stack, so a plain last-snapshot var is enough. Note
    // this snapshots the WHOLE library, so it doesn't compose with other edits made in between
    // (e.g. typing more text after a merge, then hitting undo, would also revert that typing) -
    // acceptable since the undo affordance is only shown right after the structural op itself.
    private var structuralUndoSnapshot: TeleprompterLibrary? = null
    private val _canUndoStructuralChange = MutableStateFlow(false)
    val canUndoStructuralChange: StateFlow<Boolean> = _canUndoStructuralChange.asStateFlow()

    private fun mutateWithUndoSnapshot(mutate: (TeleprompterLibrary) -> TeleprompterLibrary) {
        structuralUndoSnapshot = teleprompterLibraryFlow.value
        _canUndoStructuralChange.value = true
        updateTeleprompterLibrary(mutate)
    }

    fun undoLastStructuralChange() {
        val snapshot = structuralUndoSnapshot ?: return
        updateTeleprompterLibrary { snapshot }
        structuralUndoSnapshot = null
        _canUndoStructuralChange.value = false
    }

    // Only used for the #779-style paragraph-drop guard (observability, not a fix yet).
    private val lastParagraphCountByChapter = mutableMapOf<String, Int>()

    fun openDocument(docId: String) {
        _currentDocId.value = docId
        _focusedChapterId.value = null
    }

    fun closeDocument() {
        LogUtils.d("close document, docId=${_currentDocId.value}", tag = TAG)
        _currentDocId.value = null
        _focusedChapterId.value = null
    }

    fun setFocusedChapter(chapterId: String?) {
        val prev = _focusedChapterId.value
        if (prev == chapterId) return
        LogUtils.d(
            "chapter focus change, from=$prev, to=$chapterId, docId=${_currentDocId.value}",
            tag = TAG,
        )
        _focusedChapterId.value = chapterId
    }

    fun createDocument(): String {
        val doc = TeleprompterDocument()
        updateTeleprompterLibrary { it.copy(documents = it.documents + doc) }
        return doc.id
    }

    fun renameDocument(docId: String, title: String) {
        updateTeleprompterLibrary { lib ->
            lib.copy(documents = lib.documents.map {
                if (it.id == docId) it.copy(
                    title = title,
                    updatedAt = System.currentTimeMillis(),
                ) else it
            })
        }
    }

    fun deleteDocument(docId: String) {
        updateTeleprompterLibrary { lib -> lib.copy(documents = lib.documents.filterNot { it.id == docId }) }
        if (_currentDocId.value == docId) closeDocument()
    }

    /** Inserts right after [afterChapterId] (e.g. the currently focused chapter) if given, else appends. */
    fun addChapter(docId: String, afterChapterId: String? = null): String {
        val chapter = TeleprompterChapter()
        updateTeleprompterLibrary { lib ->
            lib.copy(documents = lib.documents.map { doc ->
                if (doc.id == docId) {
                    val insertAt = afterChapterId
                        ?.let { doc.chapters.indexOfFirst { ch -> ch.id == it } }
                        ?.takeIf { it >= 0 }
                        ?.plus(1)
                        ?: doc.chapters.size
                    doc.copy(
                        chapters = doc.chapters.toMutableList().apply { add(insertAt, chapter) },
                        updatedAt = System.currentTimeMillis(),
                    )
                } else doc
            })
        }
        return chapter.id
    }

    fun moveChapter(docId: String, chapterId: String, delta: Int) {
        updateTeleprompterLibrary { lib ->
            lib.copy(documents = lib.documents.map { doc ->
                if (doc.id != docId) return@map doc
                val from = doc.chapters.indexOfFirst { it.id == chapterId }
                val to = from + delta
                if (from < 0 || to < 0 || to >= doc.chapters.size) return@map doc
                doc.copy(
                    chapters = doc.chapters.toMutableList().apply { add(to, removeAt(from)) },
                    updatedAt = System.currentTimeMillis(),
                )
            })
        }
    }

    fun deleteChapter(docId: String, chapterId: String) {
        updateTeleprompterLibrary { lib ->
            lib.copy(documents = lib.documents.map { doc ->
                if (doc.id == docId) {
                    val remaining = doc.chapters.filterNot { it.id == chapterId }
                    doc.copy(
                        // keep at least one chapter so the editor always has something to focus
                        chapters = remaining.ifEmpty { listOf(TeleprompterChapter()) },
                        updatedAt = System.currentTimeMillis(),
                    )
                } else doc
            })
        }
        if (_focusedChapterId.value == chapterId) _focusedChapterId.value = null
    }

    fun renameChapterSubtitle(docId: String, chapterId: String, subtitle: String) {
        updateTeleprompterLibrary { lib ->
            lib.copy(documents = lib.documents.map { doc ->
                if (doc.id == docId) doc.copy(
                    chapters = doc.chapters.map { ch ->
                        if (ch.id == chapterId) ch.copy(subtitle = subtitle) else ch
                    },
                    updatedAt = System.currentTimeMillis(),
                ) else doc
            })
        }
    }

    /**
     * Called from the editor's `LaunchedEffect(state.annotatedString)`, which already debounces
     * ~800ms upstream — this function itself writes through immediately.
     */
    fun onChapterContentChanged(docId: String, chapterId: String, html: String, plainText: String) {
        // Step: debounced-autosave (or immediate blur-flush) callback firing - the user's
        // race-condition hypothesis is that this can fire AFTER a merge/split has already
        // rewritten this same chapterId's content, silently overwriting it with the stale
        // in-memory state the debounce timer captured before the merge happened. Compare this
        // timestamp against "merge write complete" below.
        TeleprompterObserveLog.log(
            "onChapterContentChanged fired, docId=$docId, chapterId=$chapterId, html=${preview(html)}",
        )
        if (html.length > HTML_LENGTH_WARN_THRESHOLD) {
            LogUtils.d(
                "chapter html length warning, docId=$docId, chapterId=$chapterId, length=${html.length}",
                tag = TAG,
            )
        } else {
            LogUtils.d(
                "chapter saved, docId=$docId, chapterId=$chapterId, htmlLength=${html.length}",
                tag = TAG,
            )
        }

        // Heuristic paragraph count via '\n' boundaries in the plain text - RichTextState's
        // real paragraph list (richParagraphList) is internal to the library, not accessible here.
        val paragraphCount = plainText.count { it == '\n' } + 1
        val prevCount = lastParagraphCountByChapter[chapterId]
        if (prevCount != null && paragraphCount < prevCount) {
            LogUtils.d(
                "paragraph count dropped (possible #779 IME merge), chapterId=$chapterId, " +
                    "before=$prevCount, after=$paragraphCount, textLength=${plainText.length}, " +
                    "textPreview=${plainText.take(200)}",
                tag = TAG,
            )
        }
        lastParagraphCountByChapter[chapterId] = paragraphCount

        updateTeleprompterLibrary { lib ->
            lib.copy(documents = lib.documents.map { doc ->
                if (doc.id == docId) doc.copy(
                    chapters = doc.chapters.map { ch ->
                        if (ch.id == chapterId) ch.copy(contentHtml = html) else ch
                    },
                    updatedAt = System.currentTimeMillis(),
                ) else doc
            })
        }
    }

    fun updateSettings(mutate: (TeleprompterSettings) -> TeleprompterSettings) {
        updateTeleprompterLibrary { it.copy(settings = mutate(it.settings)) }
    }

    fun updateLibraryBackground(mutate: (GlassBackgroundConfig) -> GlassBackgroundConfig) {
        updateTeleprompterLibrary { it.copy(libraryBackground = mutate(it.libraryBackground)) }
    }

    fun updateDocumentFontFamily(docId: String, fontFamily: TeleprompterFontFamily) {
        updateTeleprompterLibrary { lib ->
            lib.copy(documents = lib.documents.map { doc ->
                if (doc.id == docId) doc.copy(fontFamily = fontFamily, updatedAt = System.currentTimeMillis()) else doc
            })
        }
    }

    fun updateEditZoomScale(docId: String, scale: Float) {
        val clamped = scale.coerceIn(0.5f, 2f)
        updateTeleprompterLibrary { lib ->
            lib.copy(documents = lib.documents.map { doc ->
                if (doc.id == docId) doc.copy(editZoomScale = clamped, updatedAt = System.currentTimeMillis()) else doc
            })
        }
    }

    fun updateDocumentOrientation(docId: String, orientation: TeleprompterOrientation) {
        updateTeleprompterLibrary { lib ->
            lib.copy(documents = lib.documents.map { doc ->
                if (doc.id == docId) doc.copy(orientation = orientation, updatedAt = System.currentTimeMillis()) else doc
            })
        }
    }

    fun updateViewZoomScale(docId: String, scale: Float) {
        val clamped = scale.coerceIn(0.5f, 2f)
        updateTeleprompterLibrary { lib ->
            lib.copy(documents = lib.documents.map { doc ->
                if (doc.id == docId) doc.copy(viewZoomScale = clamped, updatedAt = System.currentTimeMillis()) else doc
            })
        }
    }

    fun updateSubtitleStyle(docId: String, mutate: (TeleprompterSubtitleStyle) -> TeleprompterSubtitleStyle) {
        updateTeleprompterLibrary { lib ->
            lib.copy(documents = lib.documents.map { doc ->
                if (doc.id == docId) {
                    doc.copy(subtitleStyle = mutate(doc.subtitleStyle), updatedAt = System.currentTimeMillis())
                } else {
                    doc
                }
            })
        }
    }

    // 2026-08-25 - 写入路径照抄 updateSubtitleStyle 的形状：按 docId 定位、替换 displayOverrides
    // 这一部分、其余字段不动。跟 updateSettings（改全局）是两个独立的写入口，不合并成一个——这
    // 五项现在到底是在改"这篇文档自己的"还是"全局的"，必须由调用方（设置面板每一项的切换开关）
    // 显式选择走哪一个函数，不能靠隐式判断。
    fun updateDisplayOverrides(docId: String, mutate: (TeleprompterDisplayOverrides) -> TeleprompterDisplayOverrides) {
        updateTeleprompterLibrary { lib ->
            lib.copy(documents = lib.documents.map { doc ->
                if (doc.id == docId) {
                    doc.copy(displayOverrides = mutate(doc.displayOverrides), updatedAt = System.currentTimeMillis())
                } else {
                    doc
                }
            })
        }
    }

    /** "降级为正文" (heading long-press) and the outline panel's "并入上一章" both land here -
     * same operation, two entry points. No-op if [chapterId] is the document's first chapter
     * (nothing above it to merge into). */
    fun mergeChapterIntoPreviousChapter(docId: String, chapterId: String) {
        val doc = teleprompterLibraryFlow.value.documents.find { it.id == docId } ?: return
        val idx = doc.chapters.indexOfFirst { it.id == chapterId }
        if (idx <= 0) return
        val prev = doc.chapters[idx - 1]
        val current = doc.chapters[idx]

        // Step 1: operation triggered.
        TeleprompterObserveLog.log(
            "step1 merge triggered, docId=$docId, currentChapterId=${current.id}, " +
                "prevChapterId=${prev.id}, demotedSubtitle=\"${current.subtitle}\"",
        )
        // Step 2: previous chapter's existing content, as read from the in-memory library.
        TeleprompterObserveLog.log("step2 prev.contentHtml = ${preview(prev.contentHtml)}")
        // Step 3: the chapter being demoted's content, as read from the in-memory library.
        TeleprompterObserveLog.log("step3 current.contentHtml = ${preview(current.contentHtml)}")

        val mergedHtml = mergeChapterIntoPrevious(prev, current)
        // Step 4a: merge computed, before it's written into the document's chapter list.
        TeleprompterObserveLog.log("step4a mergedHtml computed = ${preview(mergedHtml)}")

        mutateWithUndoSnapshot { lib ->
            lib.copy(documents = lib.documents.map { d ->
                if (d.id != docId) d else d.copy(
                    chapters = d.chapters.toMutableList().apply {
                        // structureVersion bump: ChapterItem's RichTextState is hydrated once
                        // per (id, structureVersion) - without this, the target chapter keeps
                        // the id it already had, so its already-hydrated state would never pick
                        // up the merged content (this was the actual bug behind "整段内容都消失
                        // 了": the underlying HTML was correct, but nothing told the UI to
                        // re-read it).
                        this[idx - 1] = prev.copy(contentHtml = mergedHtml, structureVersion = prev.structureVersion + 1)
                        removeAt(idx)
                    },
                    updatedAt = System.currentTimeMillis(),
                )
            })
        }
        // Step 4b: written into the in-memory data object (teleprompterLibraryFlow.value).
        val writtenBack = teleprompterLibraryFlow.value.documents.find { it.id == docId }
            ?.chapters?.find { it.id == prev.id }?.contentHtml
        TeleprompterObserveLog.log(
            "step4b written into data object, readBackFromFlow = " +
                (writtenBack?.let(::preview) ?: "NULL (chapter not found!)"),
        )

        if (_focusedChapterId.value == chapterId) _focusedChapterId.value = null
        LogUtils.d(
            "chapter merged into previous, docId=$docId, mergedChapterId=$chapterId, targetChapterId=${prev.id}",
            tag = TAG,
        )

        // Step 5: the store's file write is itself debounced/async (see createTextFlow's
        // conflate().debounce(0).collect{} coroutine) - it does NOT happen synchronously inside
        // updateTeleprompterLibrary above, so a naive read-back right here would just read
        // whatever was on disk before this call. Give the write coroutine a moment, then read
        // the persisted file straight off disk to confirm what actually landed there.
        viewModelScope.launch {
            delay(300)
            runCatching {
                val file = storeFolder.resolve("teleprompter_library.json")
                val text = if (file.exists()) file.readText() else null
                val diskChapterHtml = text?.let { raw ->
                    runCatching {
                        json.decodeFromString<TeleprompterLibrary>(raw)
                            .documents.find { it.id == docId }
                            ?.chapters?.find { it.id == prev.id }?.contentHtml
                    }.getOrNull()
                }
                TeleprompterObserveLog.log(
                    "step5 disk read-back, fileExists=${file.exists()}, chapterHtmlOnDisk = " +
                        (diskChapterHtml?.let(::preview) ?: "NULL (not found on disk)"),
                )
            }.onFailure { e -> TeleprompterObserveLog.log("step5 disk read-back failed: ${e.message}") }
        }
    }

    /** "提升为副标题" (body-paragraph long-press). [paragraphIndex] is 0-based, per
     * countParagraphs/paragraphIndexForCharIndex in TeleprompterChapterOps. */
    fun promoteParagraphToChapter(docId: String, chapterId: String, paragraphIndex: Int) {
        val doc = teleprompterLibraryFlow.value.documents.find { it.id == docId } ?: return
        val idx = doc.chapters.indexOfFirst { it.id == chapterId }
        if (idx < 0) return
        val split = splitChapterAtParagraph(doc.chapters[idx], paragraphIndex) ?: return
        val (updatedOriginal, newChapter) = split
        mutateWithUndoSnapshot { lib ->
            lib.copy(documents = lib.documents.map { d ->
                if (d.id != docId) d else d.copy(
                    chapters = d.chapters.toMutableList().apply {
                        // Same structureVersion bump as the merge path above - updatedOriginal
                        // keeps the source chapter's id, so its already-hydrated UI state needs
                        // an explicit signal to re-read the (now-shortened) content.
                        this[idx] = updatedOriginal.copy(structureVersion = updatedOriginal.structureVersion + 1)
                        add(idx + 1, newChapter)
                    },
                    updatedAt = System.currentTimeMillis(),
                )
            })
        }
        LogUtils.d(
            "paragraph promoted to new chapter, docId=$docId, sourceChapterId=$chapterId, newChapterId=${newChapter.id}",
            tag = TAG,
        )
    }

    /** New document from a parsed Word import - chapters/title/font/subtitle style already
     * resolved by the parser (the last one from the source doc's own heading formatting - see
     * TeleprompterWordParser's heading-run capture).
     *
     * [displayOverrides]/[editZoomScale] (2026-08-25) come from a customXml part this app itself
     * wrote on a previous export (see TeleprompterDocxSettings.kt) - default/null for plain Word
     * sources, which leaves the new document following global settings exactly like before this
     * feature existed. Written straight into THIS document only, never into library.settings -
     * that's the entire point: opening someone else's differently-styled export must not change
     * how every other document in this library looks. */
    fun createDocumentFromImport(
        title: String,
        chapters: List<TeleprompterChapter>,
        fontFamily: TeleprompterFontFamily,
        subtitleStyle: TeleprompterSubtitleStyle,
        displayOverrides: TeleprompterDisplayOverrides = TeleprompterDisplayOverrides(),
        editZoomScale: Float? = null,
    ): String {
        val doc = TeleprompterDocument(
            title = title,
            chapters = chapters.ifEmpty { listOf(TeleprompterChapter()) },
            fontFamily = fontFamily,
            subtitleStyle = subtitleStyle,
            displayOverrides = displayOverrides,
            editZoomScale = editZoomScale ?: TeleprompterDocument().editZoomScale,
        )
        updateTeleprompterLibrary { it.copy(documents = it.documents + doc) }
        return doc.id
    }
}
