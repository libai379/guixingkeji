/*
 * docx 导出携带文档设置 —— 2026-08-25。
 *
 * 范围（用户明确定的）：正文默认字号、整体缩放、行距、对齐方式、内容宽度这五项(实际生效值，
 * 不是覆盖状态本身)、编辑时显示缩放、副标题样式(字号/加粗/颜色)。不带任何 GlassTarget/液态玻璃
 * 参数。
 *
 * 实现方式：一份扁平的自定义 XML，塞进 OOXML 的 customXml 部件——这是 OOXML 规范里专门留给
 * "第三方软件塞自己的私有数据、Word/WPS 打开时完全忽略、不影响正文显示"的机制，不是挪用别的
 * 东西凑合。写入两个新 zip 条目：customXml/item1.xml（数据本身）+
 * word/_rels/document.xml.rels（声明"文档主体关联着这个自定义部件"的关系文件——这个项目至今没
 * 生成过这个文件，是全新的一份，不是改已有的）。[Content_Types].xml 不用动：里面已经有
 * `<Default Extension="xml" ContentType="application/xml"/>`，新部件是 .xml 后缀，自动落进这条
 * 默认规则。
 *
 * 带版本号（跟预设 JSON 那次同一个理由）：以后格式真的要变，新旧版本能明确区分，不是解析出一堆
 * 错乱数据才发现对不上。当前解析失败（版本不认识/结构不对/压根没有这个部件）一律返回 null，不
 * 抛异常——这份数据是锦上添花，不是 docx 能不能正常打开的必要条件，解析不出来就当没有，直接用
 * 本地设置，不能因为这一小块可选数据把整个 docx 导入搞失败。
 */
package com.guixing.teleprompter

import com.guixing.util.LogUtils
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

private const val TAG = "Prompter"
private const val DOCX_SETTINGS_FORMAT_VERSION = 1

/** customXml 自定义部件在 zip 里的路径，导出/导入两边共用同一个常量，不会对不上。 */
const val DOCX_SETTINGS_CUSTOM_XML_PATH = "customXml/item1.xml"

/** 关联 word/document.xml 和这个自定义部件的关系文件——customXml 类型的官方 relationship type。 */
const val DOCX_DOCUMENT_RELS_PATH = "word/_rels/document.xml.rels"

fun buildDocxSettingsXml(
    overrides: TeleprompterDisplayOverrides,
    subtitleStyle: TeleprompterSubtitleStyle,
): String = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<guiyueTeleprompterSettings v="$DOCX_SETTINGS_FORMAT_VERSION">""")
    overrides.fontSizeSp?.let { append("<fontSizeSp>$it</fontSizeSp>") }
    overrides.overallScale?.let { append("<overallScale>$it</overallScale>") }
    overrides.lineHeightMultiplier?.let { append("<lineHeightMultiplier>$it</lineHeightMultiplier>") }
    overrides.textAlignMode?.let { append("<textAlignMode>${it.name}</textAlignMode>") }
    overrides.contentWidthPortrait?.let { append("<contentWidthPortrait>$it</contentWidthPortrait>") }
    overrides.contentWidthLandscape?.let { append("<contentWidthLandscape>$it</contentWidthLandscape>") }
    // 2026-08-28 - editZoomScale 已删除，不再写进部件。老包里带这个标签的 docx 仍能导入：解析端
    // 只按标签名取值，多出来的标签本来就会被忽略（跟 ignoreUnknownKeys 同一个道理）。
    append("<subtitleFontSizeSp>${subtitleStyle.fontSizeSp}</subtitleFontSizeSp>")
    append("<subtitleBold>${subtitleStyle.bold}</subtitleBold>")
    // null = 跟随正文，标签整个不出现（不是写一个空标签）——解析端用"标签存不存在"判断，不是
    // 判断标签内容是否为空。
    subtitleStyle.colorArgb?.let { append("<subtitleColorArgb>$it</subtitleColorArgb>") }
    append("</guiyueTeleprompterSettings>")
}

/** 只保留"这次真的解析出了值"的字段，其余 null——跟 [TeleprompterDisplayOverrides] 是同一个"null
 *  = 不改这一项"的语义，调用方（[importDocxAndCreateDocument]）能直接把 [displayOverrides] 整个
 *  塞进新文档的 displayOverrides 字段，不用逐项判断。 */
data class ParsedDocxSettings(
    val displayOverrides: TeleprompterDisplayOverrides,
    val subtitleStyle: TeleprompterSubtitleStyle?,
)

/** 解析失败（不是这个格式/版本不认识/XML 都不合法）一律返回 null，见文件头注释——这份数据可有
 *  可无，不能因为它解析失败连累整个 docx 导入。 */
fun parseDocxSettingsXml(text: String): ParsedDocxSettings? {
    return try {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            .newDocumentBuilder().parse(InputSource(StringReader(text)))
        val root = doc.documentElement
        if (root.tagName != "guiyueTeleprompterSettings") {
            LogUtils.d("docx settings: unexpected root tag ${root.tagName}", tag = TAG)
            return null
        }
        val version = root.getAttribute("v").toIntOrNull()
        if (version != DOCX_SETTINGS_FORMAT_VERSION) {
            LogUtils.d("docx settings: unknown version $version, expected $DOCX_SETTINGS_FORMAT_VERSION", tag = TAG)
            return null
        }

        fun childText(tag: String): String? {
            val nodes = root.getElementsByTagName(tag)
            return if (nodes.length > 0) nodes.item(0).textContent else null
        }

        val subtitleFontSizeSp = childText("subtitleFontSizeSp")?.toFloatOrNull()
        val subtitleBold = childText("subtitleBold")?.toBooleanStrictOrNull()
        val subtitleStyle = if (subtitleFontSizeSp != null && subtitleBold != null) {
            TeleprompterSubtitleStyle(
                fontSizeSp = subtitleFontSizeSp,
                bold = subtitleBold,
                colorArgb = childText("subtitleColorArgb")?.toLongOrNull(),
            )
        } else {
            null
        }

        ParsedDocxSettings(
            displayOverrides = TeleprompterDisplayOverrides(
                fontSizeSp = childText("fontSizeSp")?.toFloatOrNull(),
                overallScale = childText("overallScale")?.toFloatOrNull(),
                lineHeightMultiplier = childText("lineHeightMultiplier")?.toFloatOrNull(),
                textAlignMode = childText("textAlignMode")?.let { name ->
                    TeleprompterTextAlignMode.entries.find { it.name == name }
                },
                contentWidthPortrait = childText("contentWidthPortrait")?.toFloatOrNull(),
                contentWidthLandscape = childText("contentWidthLandscape")?.toFloatOrNull(),
            ),
            subtitleStyle = subtitleStyle,
        )
    } catch (e: Exception) {
        LogUtils.d("docx settings parse failed", e, tag = TAG)
        null
    }
}

/** word/_rels/document.xml.rels 的内容——只声明这一条 customXml 关系，rId 号跟根 _rels/.rels 里
 *  的 rId1（指向 word/document.xml 本身）互相独立，两份 rels 文件各自的编号空间不冲突。 */
const val DOCUMENT_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/customXml" Target="../customXml/item1.xml"/></Relationships>"""
