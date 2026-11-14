package com.nromanovich.stealthsnake

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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
        PARAGRAPH
    }

    data class Block(
        val kind: BlockKind,
        val text: String
    )

    data class Article(
        val title: String,
        val byline: String,
        val paragraphs: List<String>,
        val blocks: List<Block> = paragraphs.map { Block(BlockKind.PARAGRAPH, it) }
    ) {
        val isEmpty: Boolean
            get() = paragraphs.isEmpty()
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
            var seen = {};
            var nodes = best ? best.querySelectorAll('h2, h3, blockquote, li, p') : [];
            for (var k = 0; k < nodes.length; k++) {
              var n = nodes[k];
              if (!isVisible(n)) continue;
              if (n.closest && n.closest('nav, header, footer, aside')) continue;
              if (n.tagName === 'P' && n.closest && n.closest('blockquote, li')) continue;
              var t = (n.innerText || '').replace(/\s+/g, ' ').trim();
              var minLen = (n.tagName === 'H2' || n.tagName === 'H3') ? 2 : 20;
              if (t.length < minLen || seen[t]) continue;
              seen[t] = true;
              var kind = 'paragraph';
              if (n.tagName === 'H2' || n.tagName === 'H3') kind = 'heading';
              else if (n.tagName === 'BLOCKQUOTE') kind = 'quote';
              else if (n.tagName === 'LI') kind = 'list_item';
              blocks.push({ kind: kind, text: t });
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
                        val text = bObj.optString("text", "").trim()
                        if (text.isEmpty()) continue
                        val kind = when (bObj.optString("kind", "paragraph")) {
                            "heading" -> BlockKind.HEADING
                            "quote" -> BlockKind.QUOTE
                            "list_item" -> BlockKind.LIST_ITEM
                            else -> BlockKind.PARAGRAPH
                        }
                        add(Block(kind, text))
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
        val bodyViews = mutableListOf<Pair<BlockKind, TextView>>()
        lateinit var smallerBtn: Button
        lateinit var largerBtn: Button

        fun applyFontSize() {
            bodyViews.forEach { (kind, tv) ->
                tv.textSize = if (kind == BlockKind.HEADING) fontSizeSp + 2f else fontSizeSp
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
                        textSize = fontSizeSp + 2f
                        setTypeface(typeface, Typeface.BOLD)
                        setLineSpacing(0f, 1.25f)
                        setTextColor(colors.text)
                        setTextIsSelectable(true)
                        setPadding(0, (10 * density).toInt(), 0, (8 * density).toInt())
                    }
                    bodyViews.add(block.kind to tv)
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
                    bodyViews.add(block.kind to tv)
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
                    bodyViews.add(block.kind to tv)
                    articleCol.addView(tv)
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
                    bodyViews.add(block.kind to tv)
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
