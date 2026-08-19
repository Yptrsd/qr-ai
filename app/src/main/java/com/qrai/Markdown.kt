package com.qrai

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan

/**
 * 轻量 Markdown + LaTeX 渲染器（零第三方依赖，纯 Spannable）。
 * 支持：粗体、斜体、行内代码、标题、列表、代码块、链接、行内公式、上下标。
 * 设计原则：简单可靠，覆盖快问快答常见场景，不做复杂嵌套。
 */
object Markdown {

    fun render(text: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        renderBody(sb, text)
        return sb
    }

    /** 把 Markdown 文本切分成"表格块"和"非表格文本块"，供混合布局使用 */
    fun splitBlocks(text: String): List<Pair<String, Boolean>> {
        val blocks = ArrayList<Pair<String, Boolean>>()
        val lines = text.split("\n")
        var i = 0
        val n = lines.size
        val textBuf = StringBuilder()

        fun flushText() {
            if (textBuf.isNotBlank()) {
                blocks.add(textBuf.toString() to false)
                textBuf.setLength(0)
            }
        }

        while (i < n) {
            val line = lines[i]
            val trimmed = line.trimStart()
            // 表格：表头 + 分隔行
            if (trimmed.startsWith("|") && i + 1 < n && isTableSeparator(lines[i + 1].trim())) {
                flushText()
                val tbl = StringBuilder()
                tbl.append(line).append("\n").append(lines[i + 1]).append("\n")
                i += 2
                while (i < n && lines[i].trimStart().startsWith("|")) {
                    tbl.append(lines[i]).append("\n")
                    i++
                }
                blocks.add(tbl.toString() to true)
                continue
            }
            textBuf.append(line).append("\n")
            i++
        }
        flushText()
        return blocks
    }

    private fun renderBody(sb: SpannableStringBuilder, text: String) {
        val lines = text.split("\n")
        var i = 0
        val n = lines.size
        while (i < n) {
            val line = lines[i]
            val trimmed = line.trimStart()
            // 代码块：``` 开头，直到下一个 ``` 或结尾
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                sb.append("\n")
                val codeStart = sb.length
                i++
                val codeLines = ArrayList<String>()
                while (i < n) {
                    if (lines[i].trimStart().startsWith("```") || lines[i].trimStart().startsWith("~~~")) {
                        i++
                        break
                    }
                    codeLines.add(lines[i])
                    i++
                }
                sb.append("    ").append(codeLines.joinToString("\n    "))
                val codeEnd = sb.length
                sb.append("\n\n")
                sb.setSpan(TypefaceSpan("monospace"), codeStart, codeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(BackgroundColorSpan(0xFFEDEDED.toInt()), codeStart, codeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }
            // 表格：`| a | b |` 开头，且下一行为分隔行 `|---|`
            if (trimmed.startsWith("|")) {
                // 探测表格起始（含表头行 + 紧跟的分隔行）
                var tableHeaderIdx = -1
                if (i + 1 < n && isTableSeparator(lines[i + 1].trim())) {
                    tableHeaderIdx = i
                }
                if (tableHeaderIdx >= 0) {
                    i += 2  // 跳过表头行和分隔行
                    // 收集数据行到空行/结束/非表格行
                    val rows = ArrayList<ArrayList<String>>()
                    while (i < n && lines[i].trimStart().startsWith("|")) {
                        rows.add(parseTableRow(lines[i]))
                        i++
                    }
                    renderTable(sb, parseTableRow(lines[tableHeaderIdx]), rows)
                    continue
                }
            }
            // 块级公式：\[ ... \] 或 $$...$$（可单行或跨行）
            if (trimmed.startsWith("\\[") || trimmed.startsWith("$$")) {
                val formulaLines = ArrayList<String>()
                var closed = false
                val blockOpen = if (trimmed.startsWith("\\[")) "\\[" else "$$"
                val blockClose = if (trimmed.startsWith("\\[")) "\\]" else "$$"
                var firstContent = trimmed.removePrefix(blockOpen).trim()
                if (firstContent.endsWith(blockClose) && firstContent.length > 1) {
                    // 单行 `\[ ... \]` 或 `$$ ... $$`
                    formulaLines.add(firstContent.dropLast(blockClose.length).trim())
                    closed = true
                } else {
                    if (firstContent.isNotEmpty()) formulaLines.add(firstContent)
                    i++
                    while (i < n) {
                        if (lines[i].trim().endsWith(blockClose)) {
                            val ci = lines[i].trim()
                            val beforeClose = ci.removeSuffix(blockClose).trim()
                            if (beforeClose.isNotEmpty()) formulaLines.add(beforeClose)
                            closed = true
                            break
                        }
                        formulaLines.add(lines[i])
                        i++
                    }
                }
                renderBlockFormula(sb, formulaLines.joinToString(" "))
                if (!closed) {
                    i = n
                } else {
                    i++
                }
                continue
            }
            renderLine(sb, line)
            i++
        }
    }

    /** 块级公式：居中、大号、斜体紫色 */
    private fun renderBlockFormula(sb: SpannableStringBuilder, formula: String) {
        sb.append("\n")
        val start = sb.length
        sb.append("    ").append(parseLatexFormula(formula))
        val end = sb.length
        sb.setSpan(RelativeSizeSpan(1.2f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(0xFF6A4FA3.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n\n")
    }

    /** 轻量 LaTeX 解析：\frac 分数、\boxed 包围、^上标(x^{n}也支持)、\sec、\left\right 等 */
    private fun parseLatexFormula(formula: String): CharSequence {
        var result = formula
        // 处理 \frac{numerator}{denominator}
        val fracRegex = Regex("\\\\frac\\{([^{}]*)\\}\\{([^{}]*)\\}")
        var guard = 0
        while (guard < 20) {
            val m = fracRegex.find(result) ?: break
            val num = m.groupValues[1].trim()
            val den = m.groupValues[2].trim()
            val replacement = "($num)/($den)"
            result = result.replaceRange(m.range, replacement)
            guard++
        }
        // 处理 \boxed{内容} → [内容]
        val boxedRegex = Regex("\\\\boxed\\{([^{}]*)\\}")
        guard = 0
        while (guard < 20) {
            val m = boxedRegex.find(result) ?: break
            val content = m.groupValues[1].trim()
            val replacement = "[$content]"
            result = result.replaceRange(m.range, replacement)
            guard++
        }
        // 处理上标 ^{n} → ⁿ（带括号的上标）和单字符 ^n
        // 先处理 ^{...} 多字符上标
        val supBraceRegex = Regex("\\^\\{([^{}]*)\\}")
        guard = 0
        while (guard < 50) {
            val m = supBraceRegex.find(result) ?: break
            val content = m.groupValues[1].trim()
            val sup = superscript(content)
            result = result.replaceRange(m.range, sup)
            guard++
        }
        // 再处理 ^x 单字符上标（排除 ^ 后是 { 或空白，这些不处理）
        val supSingleRegex = Regex("\\^(?!\\{)[^\\s^]{1}")
        guard = 0
        while (guard < 50) {
            val m = supSingleRegex.find(result) ?: break
            val content = m.value.removePrefix("^")
            result = result.replace(m.value, superscript(content))
            guard++
        }
        // 处理 \left( \right) 等 → 转换为 ( )
        result = result
            .replace("\\left(", "(")
            .replace("\\right)", ")")
            .replace("\\left[", "[")
            .replace("\\right]", "]")
            .replace("\\left\\{", "{")
            .replace("\\right\\}", "}")
            .replace("\\left|", "|")
            .replace("\\right|", "|")
            .replace("\\left\\langle", "⟨")
            .replace("\\right\\rangle", "⟩")
        // 移除其它转义命令前缀的反斜杠
        result = result
            .replace("\\cdot", "·")
            .replace("\\times", "×")
            .replace("\\pm", "±")
            .replace("\\sqrt", "√")
            .replace("\\sec", "sec")
            .replace("\\csc", "csc")
            .replace("\\cot", "cot")
            .replace("\\arcsin", "arcsin")
            .replace("\\arccos", "arccos")
            .replace("\\arctan", "arctan")
            .replace("\\pi", "π")
            .replace("\\theta", "θ")
            .replace("\\alpha", "α")
            .replace("\\beta", "β")
            .replace("\\gamma", "γ")
            .replace("\\delta", "δ")
            .replace("\\epsilon", "ε")
            .replace("\\infty", "∞")
            .replace("\\neq", "≠")
            .replace("\\leq", "≤")
            .replace("\\geq", "≥")
            .replace("\\approx", "≈")
            .replace("\\sim", "∼")
            .replace("\\sum", "∑")
            .replace("\\prod", "∏")
            .replace("\\int", "∫")
            .replace("\\Delta", "Δ")
            .replace("\\nabla", "∇")
            .replace("\\partial", "∂")
            .replace("\\log", "log")
            .replace("\\ln", "ln")
            .replace("\\sin", "sin")
            .replace("\\cos", "cos")
            .replace("\\tan", "tan")
            .replace("\\lim", "lim")
            .replace("\\max", "max")
            .replace("\\min", "min")
            .replace("\\quad", "  ")
            .replace("\\,", " ")
        return result
    }

    /** 字符串转 Unicode 上标（支持 0-9、( ) 等常见字符） */
    private fun superscript(text: String): String {
        val map = mapOf(
            '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
            '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
            'n' to 'ⁿ', 'i' to 'ⁱ',
            '(' to '⁽', ')' to '⁾',
            '+' to '⁺', '-' to '⁻', '=' to '⁼',
            'x' to 'ˣ'
        )
        return text.map { ch -> map[ch] ?: ch }.joinToString("")
    }

    /** 判断是否为表格分隔行：|---|--| */
    private fun isTableSeparator(line: String): Boolean {
        if (!line.startsWith("|")) return false
        val inner = line.removePrefix("|").removeSuffix("|").trim()
        if (inner.isEmpty()) return false
        return inner.split("|").all {
            val c = it.trim()
            c.matches(Regex("^:?-+:?$"))
        }
    }

    /** 解析一行表格为单元格数组 */
    private fun parseTableRow(line: String): ArrayList<String> {
        val cells = ArrayList<String>()
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        // 简单按 | 分割（不处理转义管线）
        for (cell in s.split("|")) {
            cells.add(cell.trim())
        }
        return cells
    }

    /** 渲染表格：斑马纹、单元格对齐、表头加粗 */
    private fun renderTable(sb: SpannableStringBuilder, header: ArrayList<String>, rows: ArrayList<ArrayList<String>>) {
        val cols = header.size
        if (cols == 0) return
        // 计算每列最大宽度（中文字符算 2 宽度）
        fun displayWidth(s: String): Int = s.fold(0) { acc, ch -> acc + if (ch.code > 127) 2 else 1 }
        val widths = IntArray(cols) { displayWidth(header[it]) }
        for (row in rows) {
            for (c in 0 until minOf(cols, row.size)) {
                widths[c] = maxOf(widths[c], displayWidth(row[c]))
            }
        }
        sb.append("\n")
        // 表头
        appendTableRow(sb, header, widths, bold = true)
        // 分隔行
        sb.append("  ").append("─".repeat(widths.sum() + cols * 3 + 1)).append("\n")
        // 数据行（斑马纹：偶数行深底）
        rows.forEachIndexed { idx, row ->
            val start = sb.length
            appendTableRow(sb, row, widths, bold = false)
            val end = sb.length
            if (idx % 2 == 0) {
                sb.setSpan(BackgroundColorSpan(0xFFF3F6FB.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        sb.append("\n")
    }

    private fun appendTableRow(sb: SpannableStringBuilder, row: ArrayList<String>, widths: IntArray, bold: Boolean) {
        val sbRow = SpannableStringBuilder()
        sbRow.append("  │ ")
        for (c in widths.indices) {
            val cellText = if (c < row.size) row[c] else ""
            sbRow.append(cellText)
            // 填充到列宽（中文宽）
            val pad = widths[c] - cellText.fold(0) { acc, ch -> acc + if (ch.code > 127) 2 else 1 }
            if (pad > 0) sbRow.append(" ".repeat(pad))
            sbRow.append(" │ ")
        }
        val start = sb.length
        sb.append(sbRow)
        val end = sb.length
        if (bold) {
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.append("\n")
    }

    private fun renderLine(sb: SpannableStringBuilder, line: String) {
        // 标题
        val h = Regex("^(#{1,6})\\s+(.+)$").find(line)
        if (h != null) {
            val lvl = h.groupValues[1].length
            val start = sb.length
            renderInline(sb, h.groupValues[2])
            val end = sb.length
            val size = when (lvl) {
                1 -> 1.6f; 2 -> 1.4f; 3 -> 1.2f; else -> 1.0f
            }
            sb.setSpan(RelativeSizeSpan(size), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n\n")
            return
        }
        // 无序列表
        val ul = Regex("^[-*+]\\s+(.+)$").find(line)
        if (ul != null) {
            sb.append("  •  ")
            renderInline(sb, ul.groupValues[1])
            sb.append("\n")
            return
        }
        // 有序列表
        val ol = Regex("^(\\d+)[.、)]\\s+(.+)$").find(line)
        if (ol != null) {
            val start = sb.length
            sb.append("   ").append(ol.groupValues[1]).append(". ")
            renderInline(sb, ol.groupValues[2])
            sb.append("\n")
            return
        }
        // 引用
        val q = Regex("^>\\s?(.*)$").find(line)
        if (q != null) {
            val start = sb.length
            sb.append("│ ")
            renderInline(sb, q.groupValues[1])
            val end = sb.length
            sb.setSpan(ForegroundColorSpan(0xFF666666.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n")
            return
        }
        // 分隔线
        if (line.matches(Regex("^[-*_]{3,}$"))) {
            sb.append("――――――――\n")
            return
        }
        // 空行
        if (line.isBlank()) {
            sb.append("\n")
            return
        }
        // 普通行
        renderInline(sb, line)
        sb.append("\n")
    }

    /** 行内解析：处理 `代码`、`$公式$`、`**粗体**`、`*斜体*`、`~~删除~~`、`^上标^`、`~下标~`、`[链接](url)` */
    private fun renderInline(sb: SpannableStringBuilder, text: String) {
        var i = 0
        val n = text.length
        val plain = StringBuilder()

        fun flush() {
            if (plain.isNotEmpty()) {
                sb.append(plain)
                plain.setLength(0)
            }
        }

        // 优先处理保护段（代码、公式、链接），内部不再解析其他语法
        while (i < n) {
            val c = text[i]
            // 行内代码 `...`
            if (c == '`') {
                val close = text.indexOf('`', i + 1)
                if (close > 0) {
                    flush()
                    val start = sb.length
                    sb.append(text.substring(i + 1, close))
                    val end = sb.length
                    sb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(0xFFE8E8E8.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = close + 1
                    continue
                }
            }
            // 行内公式 $...$
            if (c == '$') {
                val close = text.indexOf('$', i + 1)
                if (close > 0) {
                    flush()
                    val start = sb.length
                    // 行内公式也处理 \frac 分数及其他命令
                    sb.append(parseLatexFormula(text.substring(i + 1, close)))
                    val end = sb.length
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(0xFF6A4FA3.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = close + 1
                    continue
                }
            }
            // 行内公式 \(...\)（等同于 $...$）
            if (c == '\\' && i + 1 < n && text[i + 1] == '(') {
                val close = text.indexOf("\\)", i + 2)
                if (close > 0) {
                    flush()
                    val start = sb.length
                    sb.append(parseLatexFormula(text.substring(i + 2, close)))
                    val end = sb.length
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(0xFF6A4FA3.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = close + 2
                    continue
                }
            }
            // 链接 [label](url)
            if (c == '[') {
                val m = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)").find(text, i)
                if (m != null) {
                    flush()
                    val start = sb.length
                    sb.append(m.groupValues[1])
                    val end = sb.length
                    sb.setSpan(ForegroundColorSpan(0xFF1A73E8.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = m.range.last + 1
                    continue
                }
            }
            // 粗体 **..**
            if (text.startsWith("**", i)) {
                val close = text.indexOf("**", i + 2)
                if (close > 0) {
                    flush()
                    val start = sb.length
                    renderInline(sb, text.substring(i + 2, close))
                    val end = sb.length
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = close + 2
                    continue
                }
            }
            // 删除线 ~~..~~
            if (text.startsWith("~~", i)) {
                val close = text.indexOf("~~", i + 2)
                if (close > 0) {
                    flush()
                    val start = sb.length
                    sb.append(text.substring(i + 2, close))
                    val end = sb.length
                    sb.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = close + 2
                    continue
                }
            }
            // 斜体 *..* / _.._
            if ((c == '*' || c == '_') && i + 1 < n) {
                val next = text.indexOf(if (c == '*') '*' else '_', i + 1)
                // 排除紧邻**粗体**的情况（已是上面的分支）
                if (next > 0 && next > i + 1) {
                    val innerLen = next - i - 1
                    if (innerLen > 0) {
                        flush()
                        val start = sb.length
                        sb.append(text.substring(i + 1, next))
                        val end = sb.length
                        sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = next + 1
                        continue
                    }
                }
            }
            // 上标 x^y （不含空格，一个字符或括号）
            if (c == '^' && i + 1 < n) {
                val j = text.indexOf('^', i + 1)
                if (j > 0) {
                    val innerLen = j - i - 1
                    if (innerLen > 0 && innerLen <= 3) { // 限制长度避免误伤
                        flush()
                        val start = sb.length
                        sb.append(text.substring(i + 1, j))
                        val end = sb.length
                        sb.setSpan(RelativeSizeSpan(0.7f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = j + 1
                        continue
                    }
                }
            }
            plain.append(c)
            i++
        }
        flush()
    }
}
