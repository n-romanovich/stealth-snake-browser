package com.nromanovich.stealthsnake

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import org.json.JSONObject

/**
 * Extracts readable article content from the active WebView page on demand
 * without injecting persistent background listeners or executing untrusted HTML.
 */
object ReaderModeHelper {

    enum class BlockKind {
        HEADING,
        QUOTE,
        LIST_ITEM,
        CODE,
        TABLE,
        PARAGRAPH
    }

    data class Block(
        val kind: BlockKind,
        val text: String,
        val headingLevel: Int = 2,
        val tableRows: List<List<String>> = emptyList()
    )

    data class Article(
        val title: String,
        val byline: String,
        val paragraphs: List<String>,
        val blocks: List<Block> = paragraphs.map { Block(BlockKind.PARAGRAPH, it) }
    ) {
        val isEmpty: Boolean
            get() = paragraphs.isEmpty() && blocks.isEmpty()
    }

    private const val EXTRACT_SCRIPT = """
        (function() {
          try {
            function isVisible(el) {
              if (!el) return false;
              if (el.getAttribute && el.getAttribute('aria-hidden') === 'true') return false;
              if (el.offsetParent === null && el.tagName !== 'BODY') {
                var st = window.getComputedStyle ? window.getComputedStyle(el) : null;
                if (!st || st.position !== 'fixed') return false;
              }
              var style = window.getComputedStyle ? window.getComputedStyle(el) : null;
              if (style && (style.display === 'none' || style.visibility === 'hidden')) return false;
              return true;
            }
            var titleEl = document.querySelector('article h1, main h1, h1');
            var title = (titleEl && titleEl.innerText) || document.title || '';
            var metaAuthor = document.querySelector('meta[name="author"], meta[property="article:author"]');
            var byline = (metaAuthor && metaAuthor.getAttribute('content')) || (location && location.hostname) || '';

            var semantic = Array.prototype.slice.call(document.querySelectorAll('article, main, [role="main"]'));
            var fallbacks = semantic.length > 0 ? semantic : Array.prototype.slice.call(document.querySelectorAll('.post-content, .article-body, .entry-content, section, div'));
            var best = document.body;
            var bestScore = 0;
            for (var i = 0; i < fallbacks.length; i++) {
              var el = fallbacks[i];
              if (!isVisible(el)) continue;
              var ps = el.querySelectorAll('p');
              var score = 0;
              for (var j = 0; j < ps.length; j++) {
                if (!isVisible(ps[j])) continue;
                var txt = (ps[j].innerText || '').trim();
                if (txt.length >= 40) score += txt.length;
              }
              if (el.tagName === 'ARTICLE' || el.tagName === 'MAIN') score *= 1.4;
              if (score > bestScore) {
                bestScore = score;
                best = el;
              }
            }

            var blocks = [];
            var seen = Object.create(null);
            var nodes = best ? best.querySelectorAll('h1, h2, h3, h4, h5, h6, pre, code, table, blockquote, li, p') : [];
            for (var k = 0; k < nodes.length; k++) {
              var n = nodes[k];
              if (!isVisible(n)) continue;
              if (n.closest && n.closest('nav, header, footer, aside')) continue;
              var tag = n.tagName;
              if (tag === 'TABLE' && n.parentElement && n.parentElement.closest && n.parentElement.closest('table')) continue;
              if (tag !== 'TABLE' && n.closest && n.closest('table')) continue;
              if (tag === 'PRE' && n.parentElement && n.parentElement.closest && n.parentElement.closest('pre')) continue;
              if (tag !== 'PRE' && n.closest && n.closest('pre')) continue;
              if (tag === 'P' && n.closest && n.closest('blockquote, li')) continue;
              if (tag === 'TABLE') {
                var trs = n.querySelectorAll('tr');
                var rows = [];
                for (var r = 0; r < trs.length && r < 60; r++) {
                  var cells = trs[r].querySelectorAll('th, td');
                  if (!cells.length) continue;
                  var row = [];
                  for (var c = 0; c < cells.length && c < 12; c++) {
                    row.push((cells[c].innerText || '').replace(/\s+/g, ' ').trim());
                  }
                  if (row.some(function(cell){ return cell.length > 0; })) rows.push(row);
                }
                if (!rows.length) continue;
                var flat = rows.map(function(row){ return row.join(' | '); }).join('\n');
                if (seen['tbl:' + flat]) continue;
                seen['tbl:' + flat] = true;
                blocks.push({ kind: 'table', text: flat, rows: rows });
                continue;
              }
              if (tag === 'PRE' || tag === 'CODE') {
                if (tag === 'CODE' && n.closest && n.closest('p, li, h1, h2, h3, h4, h5, h6, blockquote')) {
                  continue;
                }
                var codeText = (n.textContent || '').replace(/\r\n/g, '\n').trim();
                if (codeText.length < 4 || seen['code:' + codeText]) continue;
                seen['code:' + codeText] = true;
                blocks.push({ kind: 'code', text: codeText });
                continue;
              }
              var isHeading = /^H[1-6]$/.test(tag);
              var t = (n.innerText || '').replace(/\s+/g, ' ').trim();
              var minLen = isHeading ? 2 : (tag === 'LI' ? 3 : 20);
              if (t.length < minLen || seen[t]) continue;
              seen[t] = true;
              if (isHeading) {
                blocks.push({ kind: 'heading', level: parseInt(tag.substring(1), 10) || 2, text: t });
              } else if (tag === 'BLOCKQUOTE') {
                blocks.push({ kind: 'quote', text: t });
              } else if (tag === 'LI') {
                blocks.push({ kind: 'list_item', text: t });
              } else {
                blocks.push({ kind: 'paragraph', text: t });
              }
            }

            if (blocks.length === 0 && best) {
              var fallback = (best.innerText || '').split(/\n+/).map(function(s){ return s.trim(); }).filter(function(s){ return s.length >= 25; });
              for (var m = 0; m < Math.min(fallback.length, 120); m++) {
                blocks.push({ kind: 'paragraph', text: fallback[m] });
              }
            }

            var limited = blocks.slice(0, 200);
            return JSON.stringify({
              title: title.trim(),
              byline: byline.trim(),
              paragraphs: limited.map(function(b){ return b.text; }),
              blocks: limited
            });
          } catch (e) {
            return JSON.stringify({ title: '', byline: '', paragraphs: [], blocks: [] });
          }
        })();
    """

    fun extract(webView: WebView, onResult: (Article?) -> Unit) {
        webView.evaluateJavascript(EXTRACT_SCRIPT) { raw ->
            onResult(parseEvaluateResult(raw))
        }
    }

    internal fun parseEvaluateResult(rawJsResult: String?): Article? {
        if (rawJsResult.isNullOrBlank() || rawJsResult == "null") return null
        val unescaped = runCatching {
            if (rawJsResult.startsWith("\"") && rawJsResult.endsWith("\"")) {
                JSONObject("{\"v\":$rawJsResult}").getString("v")
            } else {
                rawJsResult
            }
        }.getOrNull() ?: return null

        return runCatching {
            val obj = JSONObject(unescaped)
            val title = obj.optString("title", "").trim()
            val byline = obj.optString("byline", "").trim()
            val arr = obj.optJSONArray("paragraphs")
            val paragraphs = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val p = arr.optString(i, "").trim()
                        if (p.isNotEmpty()) add(p)
                    }
                }
            }
            val blocksArr = obj.optJSONArray("blocks")
            val blocks = if (blocksArr != null) {
                buildList {
                    for (i in 0 until blocksArr.length()) {
                        val bObj = blocksArr.optJSONObject(i) ?: continue
                        val kind = when (bObj.optString("kind", "paragraph")) {
                            "heading" -> BlockKind.HEADING
                            "quote" -> BlockKind.QUOTE
                            "list_item" -> BlockKind.LIST_ITEM
                            "code" -> BlockKind.CODE
                            "table" -> BlockKind.TABLE
                            else -> BlockKind.PARAGRAPH
                        }
                        val headingLevel = if (kind == BlockKind.HEADING) {
                            bObj.optInt("level", 2).coerceIn(1, 6)
                        } else {
                            2
                        }
                        val tableRows = if (kind == BlockKind.TABLE) {
                            val rowsJson = bObj.optJSONArray("rows")
                            buildList {
                                if (rowsJson != null) {
                                    for (r in 0 until rowsJson.length()) {
                                        val rowJson = rowsJson.optJSONArray(r) ?: continue
                                        val row = buildList {
                                            for (c in 0 until rowJson.length()) {
                                                add(rowJson.optString(c, "").trim())
                                            }
                                        }
                                        if (row.any { it.isNotEmpty() }) add(row)
                                    }
                                }
                            }
                        } else {
                            emptyList()
                        }
                        val rawText = bObj.optString("text", "").trim()
                        val finalText = if (rawText.isEmpty() && tableRows.isNotEmpty()) {
                            tableRows.joinToString("\n") { it.joinToString(" | ") }
                        } else {
                            rawText
                        }
                        if (finalText.isEmpty()) continue
                        add(Block(kind = kind, text = finalText, headingLevel = headingLevel, tableRows = tableRows))
                    }
                }
            } else {
                paragraphs.map { Block(BlockKind.PARAGRAPH, it) }
            }
            Article(title = title, byline = byline, paragraphs = paragraphs, blocks = blocks)
        }.getOrNull()
    }

    fun buildOverlayView(
        activity: Activity,
        palette: ThemePalette,
        article: Article,
        onClose: () -> Unit
    ): View {
        val density = activity.resources.displayMetrics.density
        val colors = SnakeTheme.resolveBrowserColors(activity, palette)
        var fontSizeSp = 17f
        val overlay = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.bg)
            isClickable = true
            isFocusable = true
        }
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colors.toolbar)
            setPadding((16 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }
        val headerLabel = TextView(activity).apply {
            text = activity.getString(R.string.btn_reader_mode)
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colors.text)
        }
        val bodyViews = mutableListOf<Triple<BlockKind, Int, TextView>>()
        lateinit var smallerBtn: Button
        lateinit var largerBtn: Button

        fun resolveTextSize(kind: BlockKind, headingLevel: Int): Float = when (kind) {
            BlockKind.HEADING -> when (headingLevel.coerceIn(1, 6)) {
                1 -> fontSizeSp + 4f
                2 -> fontSizeSp + 2f
                3 -> fontSizeSp + 1f
                else -> fontSizeSp
            }
            BlockKind.CODE -> (fontSizeSp - 2.5f).coerceAtLeast(11f)
            BlockKind.TABLE -> (fontSizeSp - 2f).coerceAtLeast(11.5f)
            else -> fontSizeSp
        }

        fun applyFontSize() {
            bodyViews.forEach { (kind, level, tv) ->
                tv.textSize = resolveTextSize(kind, level)
            }
            smallerBtn.isEnabled = fontSizeSp > 13f
            smallerBtn.alpha = if (smallerBtn.isEnabled) 1f else 0.4f
            largerBtn.isEnabled = fontSizeSp < 26f
            largerBtn.alpha = if (largerBtn.isEnabled) 1f else 0.4f
        }

        smallerBtn = Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
            text = "A-"
            isAllCaps = false
            minimumWidth = (48 * density).toInt()
            minWidth = (48 * density).toInt()
            setTextColor(colors.accent)
            setOnClickListener {
                fontSizeSp = (fontSizeSp - 2f).coerceAtLeast(13f)
                applyFontSize()
            }
        }
        largerBtn = Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
            text = "A+"
            isAllCaps = false
            minimumWidth = (48 * density).toInt()
            minWidth = (48 * density).toInt()
            setTextColor(colors.accent)
            setOnClickListener {
                fontSizeSp = (fontSizeSp + 2f).coerceAtMost(26f)
                applyFontSize()
            }
        }
        val closeBtn = Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
            text = activity.getString(R.string.reader_close)
            isAllCaps = false
            setTextColor(colors.muted)
            setOnClickListener { onClose() }
        }
        topBar.addView(headerLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(smallerBtn)
        topBar.addView(largerBtn)
        topBar.addView(closeBtn)
        overlay.addView(topBar)

        overlay.addView(View(activity).apply {
            setBackgroundColor(colors.stroke)
            alpha = 0.25f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, density.toInt()))
        })

        val articleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, (36 * density).toInt())
        }
        if (article.title.isNotEmpty()) {
            articleCol.addView(TextView(activity).apply {
                text = article.title
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colors.text)
                setTextIsSelectable(true)
                setPadding(0, 0, 0, (6 * density).toInt())
            })
        }
        if (article.byline.isNotEmpty()) {
            articleCol.addView(TextView(activity).apply {
                text = article.byline
                textSize = 13f
                setTextColor(colors.muted)
                setTextIsSelectable(true)
                setPadding(0, 0, 0, (18 * density).toInt())
            })
        }

        val blocks = article.blocks.ifEmpty { article.paragraphs.map { Block(BlockKind.PARAGRAPH, it) } }
        for (block in blocks) {
            when (block.kind) {
                BlockKind.HEADING -> {
                    val tv = TextView(activity).apply {
                        text = block.text
                        textSize = resolveTextSize(block.kind, block.headingLevel)
                        setTypeface(typeface, Typeface.BOLD)
                        setLineSpacing(0f, 1.25f)
                        setTextColor(colors.text)
                        setTextIsSelectable(true)
                        setPadding(0, (10 * density).toInt(), 0, (8 * density).toInt())
                    }
                    bodyViews.add(Triple(block.kind, block.headingLevel, tv))
                    articleCol.addView(tv)
                }
                BlockKind.QUOTE -> {
                    val quoteRow = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, (4 * density).toInt(), 0, (14 * density).toInt())
                    }
                    val bar = View(activity).apply {
                        setBackgroundColor(colors.accent)
                        layoutParams = LinearLayout.LayoutParams((3 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                            marginEnd = (12 * density).toInt()
                        }
                    }
                    val tv = TextView(activity).apply {
                        text = block.text
                        textSize = fontSizeSp
                        setTypeface(typeface, Typeface.ITALIC)
                        setLineSpacing(0f, 1.35f)
                        setTextColor(colors.muted)
                        setTextIsSelectable(true)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    quoteRow.addView(bar)
                    quoteRow.addView(tv)
                    bodyViews.add(Triple(block.kind, block.headingLevel, tv))
                    articleCol.addView(quoteRow)
                }
                BlockKind.LIST_ITEM -> {
                    val tv = TextView(activity).apply {
                        text = "•  ${block.text}"
                        textSize = fontSizeSp
                        setLineSpacing(0f, 1.35f)
                        setTextColor(colors.text)
                        setTextIsSelectable(true)
                        setPadding((8 * density).toInt(), 0, 0, (10 * density).toInt())
                    }
                    bodyViews.add(Triple(block.kind, block.headingLevel, tv))
                    articleCol.addView(tv)
                }
                BlockKind.CODE -> {
                    val codeScroll = HorizontalScrollView(activity).apply {
                        isHorizontalScrollBarEnabled = true
                        background = SnakeTheme.roundedSurface(colors.card, colors.stroke, 12f, density)
                        val padH = (12 * density).toInt()
                        val padV = (10 * density).toInt()
                        setPadding(padH, padV, padH, padV)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (14 * density).toInt()
                        }
                    }
                    val tv = TextView(activity).apply {
                        text = block.text
                        typeface = Typeface.MONOSPACE
                        textSize = resolveTextSize(block.kind, block.headingLevel)
                        setLineSpacing(0f, 1.3f)
                        setTextColor(colors.text)
                        setTextIsSelectable(true)
                        setHorizontallyScrolling(true)
                    }
                    codeScroll.addView(tv)
                    bodyViews.add(Triple(block.kind, block.headingLevel, tv))
                    articleCol.addView(codeScroll)
                }
                BlockKind.TABLE -> {
                    val tableScroll = HorizontalScrollView(activity).apply {
                        isHorizontalScrollBarEnabled = true
                        background = SnakeTheme.roundedSurface(colors.card, colors.stroke, 12f, density)
                        val pad = (8 * density).toInt()
                        setPadding(pad, pad, pad, pad)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (14 * density).toInt()
                        }
                    }
                    val tableLayout = TableLayout(activity).apply {
                        isShrinkAllColumns = false
                    }
                    val rows = block.tableRows.ifEmpty {
                        block.text.lines()
                            .filter { it.isNotBlank() }
                            .map { line -> line.split('|').map { it.trim() } }
                    }
                    val cellPadH = (10 * density).toInt()
                    val cellPadV = (6 * density).toInt()
                    rows.forEachIndexed { rIdx, rowCells ->
                        val tableRow = TableRow(activity)
                        for (cellText in rowCells) {
                            val cellTv = TextView(activity).apply {
                                text = cellText
                                textSize = resolveTextSize(block.kind, block.headingLevel)
                                setTypeface(null, if (rIdx == 0) Typeface.BOLD else Typeface.NORMAL)
                                setTextColor(if (rIdx == 0) colors.accent else colors.text)
                                setTextIsSelectable(true)
                                setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
                            }
                            bodyViews.add(Triple(block.kind, block.headingLevel, cellTv))
                            tableRow.addView(cellTv)
                        }
                        tableLayout.addView(tableRow)
                    }
                    tableScroll.addView(tableLayout)
                    articleCol.addView(tableScroll)
                }
                BlockKind.PARAGRAPH -> {
                    val tv = TextView(activity).apply {
                        text = block.text
                        textSize = fontSizeSp
                        setLineSpacing(0f, 1.38f)
                        setTextColor(colors.text)
                        setTextIsSelectable(true)
                        setPadding(0, 0, 0, (14 * density).toInt())
                    }
                    bodyViews.add(Triple(block.kind, block.headingLevel, tv))
                    articleCol.addView(tv)
                }
            }
        }
        applyFontSize()
        val scroll = ScrollView(activity).apply { addView(articleCol) }
        overlay.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return overlay
    }
}
