package com.nromanovich.stealthsnake

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import java.util.Locale

enum class FixCategory(@StringRes val labelRes: Int) {
    ALL(R.string.fixes_cat_all),
    NETWORK(R.string.nav_network),
    ANTIFP(R.string.nav_antifp),
    BEHAVIORAL(R.string.nav_behavioral),
    VPN(R.string.nav_vpn),
    RENDER_UI(R.string.fixes_cat_render_ui),
    SESSION(R.string.nav_session)
}

data class FixGuideEntry(
    val id: String,
    val category: FixCategory,
    @StringRes val titleRes: Int,
    @StringRes val symptomsRes: Int,
    @StringRes val causeRes: Int,
    @StringRes val fixPathRes: Int,
    val keywordsEn: List<String>,
    val keywordsRu: List<String>
)

data class FixGuideMatch(
    val entry: FixGuideEntry,
    val title: String,
    val symptoms: String,
    val cause: String,
    val fixPath: String,
    val score: Int
)

/**
 * Bilingual (EN + RU) troubleshooting knowledge base and search engine
 * covering every setting in Stealth Snake.
 */
object SiteFixGuide {

    val entries: List<FixGuideEntry> = listOf(
        FixGuideEntry(
            id = "canvas_webgl_captcha",
            category = FixCategory.ANTIFP,
            titleRes = R.string.fix_canvas_title,
            symptomsRes = R.string.fix_canvas_symptoms,
            causeRes = R.string.fix_canvas_cause,
            fixPathRes = R.string.fix_canvas_fix,
            keywordsEn = listOf(
                "canvas", "webgl", "captcha", "cloudflare", "turnstile", "hcaptcha", "recaptcha",
                "datadome", "arkose", "figma", "mapbox", "leaflet", "map", "2d", "3d", "editor",
                "photoshop", "getimagedata", "todataurl", "readpixels", "noise", "pixel", "blur",
                "bot check", "human verification", "login loop", "max stealth", "antifp"
            ),
            keywordsRu = listOf(
                "канвас", "вебгл", "капча", "каптча", "клаудфлер", "проверка", "робот", "бот",
                "вход", "зацикливается", "карта", "карты", "редактор", "фото", "рисование",
                "пиксели", "артефакты", "шум", "3д", "графика", "отпечаток", "фингерпринт"
            )
        ),
        FixGuideEntry(
            id = "webrtc_calls",
            category = FixCategory.NETWORK,
            titleRes = R.string.fix_webrtc_title,
            symptomsRes = R.string.fix_webrtc_symptoms,
            causeRes = R.string.fix_webrtc_cause,
            fixPathRes = R.string.fix_webrtc_fix,
            keywordsEn = listOf(
                "webrtc", "ice", "stun", "turn", "rtcpeerconnection", "video call", "voice call",
                "audio call", "meet", "google meet", "zoom", "discord", "jitsi", "telegram",
                "screen share", "p2p", "snapdrop", "sharedrop", "webtorrent", "mic", "camera",
                "ice candidate", "ip leak"
            ),
            keywordsRu = listOf(
                "вебртс", "звонок", "звонки", "видеозвонок", "аудиозвонок", "конференция",
                "мит", "зум", "дискорд", "телеграм", "демонстрация экрана", "экран", "микрофон",
                "камера", "п2п", "передача файлов", "созвон", "голос", "локальный ip"
            )
        ),
        FixGuideEntry(
            id = "builtin_blocklist_breakage",
            category = FixCategory.NETWORK,
            titleRes = R.string.fix_blocklist_title,
            symptomsRes = R.string.fix_blocklist_symptoms,
            causeRes = R.string.fix_blocklist_cause,
            fixPathRes = R.string.fix_blocklist_fix,
            keywordsEn = listOf(
                "blocklist", "adblock", "tracker", "oauth", "sso", "login", "sign in", "stripe",
                "paypal", "checkout", "payment", "paywall", "widget", "comment", "disqus",
                "analytics", "googletagmanager", "cdn", "403", "blocked", "player", "video"
            ),
            keywordsRu = listOf(
                "блоклист", "блокировщик", "реклама", "трекер", "вход", "авторизация", "логин",
                "войти через", "оплата", "касса", "чекаут", "виджет", "комментарии", "плеер",
                "не грузится", "пустой блок", "кнопка не нажимается", "аналитика"
            )
        ),
        FixGuideEntry(
            id = "custom_blocklist_overblock",
            category = FixCategory.NETWORK,
            titleRes = R.string.fix_custom_rules_title,
            symptomsRes = R.string.fix_custom_rules_symptoms,
            causeRes = R.string.fix_custom_rules_cause,
            fixPathRes = R.string.fix_custom_rules_fix,
            keywordsEn = listOf(
                "custom blocklist", "custom rules", "domain", "substring", "css", "fonts", "gstatic",
                "broken layout", "missing images", "api", "rule", "filter", "overblock"
            ),
            keywordsRu = listOf(
                "свои правила", "пользовательский список", "домен", "поддомен", "шрифты", "стили",
                "картинки пропали", "сломался сайт", "апи", "фильтр", "лишнее блокирует"
            )
        ),
        FixGuideEntry(
            id = "beacons_telemetry",
            category = FixCategory.NETWORK,
            titleRes = R.string.fix_beacons_title,
            symptomsRes = R.string.fix_beacons_symptoms,
            causeRes = R.string.fix_beacons_cause,
            fixPathRes = R.string.fix_beacons_fix,
            keywordsEn = listOf(
                "beacon", "sendbeacon", "ping", "telemetry", "draft", "autosave", "logout",
                "form submit", "analytics", "unload", "beforeunload", "antibot"
            ),
            keywordsRu = listOf(
                "маяк", "бикон", "телеметрия", "черновик", "автосохранение", "выход", "форма",
                "не сохраняется", "при закрытии", "отправка", "фоновый запрос"
            )
        ),
        FixGuideEntry(
            id = "audio_synth",
            category = FixCategory.ANTIFP,
            titleRes = R.string.fix_audio_title,
            symptomsRes = R.string.fix_audio_symptoms,
            causeRes = R.string.fix_audio_cause,
            fixPathRes = R.string.fix_audio_fix,
            keywordsEn = listOf(
                "audio", "webaudio", "audiocontext", "analysernode", "oscillator", "synth",
                "synthesizer", "daw", "equalizer", "visualizer", "sound", "music", "distortion",
                "crackle", "noise", "mic test", "fingerprint"
            ),
            keywordsRu = listOf(
                "аудио", "звук", "вебаудио", "синтезатор", "эквалайзер", "визуализация",
                "хрип", "треск", "искажение", "музыка", "тест микрофона", "шум", "отпечаток звука"
            )
        ),
        FixGuideEntry(
            id = "visibility_focus",
            category = FixCategory.ANTIFP,
            titleRes = R.string.fix_visibility_title,
            symptomsRes = R.string.fix_visibility_symptoms,
            causeRes = R.string.fix_visibility_cause,
            fixPathRes = R.string.fix_visibility_fix,
            keywordsEn = listOf(
                "visibility", "visibilitystate", "hidden", "hasfocus", "focus", "blur", "youtube",
                "twitch", "video", "pause", "background", "tab", "notification", "chat", "quiz",
                "exam", "proctoring", "validation", "input"
            ),
            keywordsRu = listOf(
                "видимость", "фокус", "вкладка", "свернул", "фон", "пауза", "видео", "ютуб",
                "твич", "уведомления", "чат", "экзамен", "тест", "прокторинг", "поле ввода",
                "валидация", "не останавливается", "активная вкладка"
            )
        ),
        FixGuideEntry(
            id = "viewport_layout",
            category = FixCategory.ANTIFP,
            titleRes = R.string.fix_viewport_title,
            symptomsRes = R.string.fix_viewport_symptoms,
            causeRes = R.string.fix_viewport_cause,
            fixPathRes = R.string.fix_viewport_fix,
            keywordsEn = listOf(
                "viewport", "resize", "keyboard", "layout", "css", "media query", "responsive",
                "rotate", "landscape", "portrait", "fullscreen", "modal", "popup", "clipped",
                "innerwidth", "devicepixelratio", "dpr", "zoom", "overlap", "input covered"
            ),
            keywordsRu = listOf(
                "вьюпорт", "экран", "клавиатура", "перекрывает", "вёрстка", "верстка", "съехала",
                "поворот", "альбомная", "портретная", "полный экран", "модальное окно", "обрезано",
                "масштаб", "координаты", "тап", "кнопка уехала"
            )
        ),
        FixGuideEntry(
            id = "sensors_ar_gyro",
            category = FixCategory.ANTIFP,
            titleRes = R.string.fix_sensors_title,
            symptomsRes = R.string.fix_sensors_symptoms,
            causeRes = R.string.fix_sensors_cause,
            fixPathRes = R.string.fix_sensors_fix,
            keywordsEn = listOf(
                "sensor", "gyroscope", "accelerometer", "devicemotion", "deviceorientation",
                "compass", "360", "vr", "ar", "panorama", "tilt", "game", "battery", "fraud",
                "banking", "captcha"
            ),
            keywordsRu = listOf(
                "сенсор", "датчик", "гироскоп", "акселерометр", "компас", "панорама", "360",
                "вр", "ар", "наклон", "игра", "батарея", "антифрод", "банк"
            )
        ),
        FixGuideEntry(
            id = "perf_timing",
            category = FixCategory.ANTIFP,
            titleRes = R.string.fix_perf_title,
            symptomsRes = R.string.fix_perf_symptoms,
            causeRes = R.string.fix_perf_cause,
            fixPathRes = R.string.fix_perf_fix,
            keywordsEn = listOf(
                "performance", "performance.now", "timer", "raf", "requestanimationframe", "fps",
                "stutter", "lag", "jitter", "animation", "game", "speedtest", "ping", "latency",
                "desync", "timing", "20us"
            ),
            keywordsRu = listOf(
                "таймер", "анимация", "дёргается", "дергается", "тормозит", "лагает", "фпс",
                "кадры", "игра", "спидтест", "пинг", "задержка", "рассинхрон", "производительность"
            )
        ),
        FixGuideEntry(
            id = "micro_jitter_scroll",
            category = FixCategory.BEHAVIORAL,
            titleRes = R.string.fix_jitter_title,
            symptomsRes = R.string.fix_jitter_symptoms,
            causeRes = R.string.fix_jitter_cause,
            fixPathRes = R.string.fix_jitter_fix,
            keywordsEn = listOf(
                "micro-jitter", "jitter", "scroll", "idle", "dropdown", "select", "autocomplete",
                "selection", "reading", "twitch", "behavioral", "session timeout", "interval"
            ),
            keywordsRu = listOf(
                "микроджиттер", "джиттер", "скролл", "дёргает", "дергает", "прокрутка", "чтение",
                "выпадающий список", "автодополнение", "выделение текста", "простой", "поведение"
            )
        ),
        FixGuideEntry(
            id = "external_schemes",
            category = FixCategory.BEHAVIORAL,
            titleRes = R.string.fix_schemes_title,
            symptomsRes = R.string.fix_schemes_symptoms,
            causeRes = R.string.fix_schemes_cause,
            fixPathRes = R.string.fix_schemes_fix,
            keywordsEn = listOf(
                "intent", "tel", "mailto", "tg", "whatsapp", "geo", "bank", "deep link",
                "external app", "open in app", "scheme", "redirect", "oauth", "sbp", "qr"
            ),
            keywordsRu = listOf(
                "внешнее приложение", "открыть в приложении", "схема", "ссылка", "телеграм",
                "звонок", "почта", "банк", "сбп", "оплата", "карта", "диплинк", "редирект"
            )
        ),
        FixGuideEntry(
            id = "js_dialogs",
            category = FixCategory.BEHAVIORAL,
            titleRes = R.string.fix_dialogs_title,
            symptomsRes = R.string.fix_dialogs_symptoms,
            causeRes = R.string.fix_dialogs_cause,
            fixPathRes = R.string.fix_dialogs_fix,
            keywordsEn = listOf(
                "alert", "confirm", "prompt", "dialog", "popup", "delete", "logout", "admin",
                "router", "phpmyadmin", "button does nothing", "javascript"
            ),
            keywordsRu = listOf(
                "диалог", "всплывающее окно", "подтверждение", "удалить", "выйти", "админка",
                "роутер", "кнопка не работает", "ничего не происходит", "алерт", "промпт"
            )
        ),
        FixGuideEntry(
            id = "vpn_kill_switch_blocked",
            category = FixCategory.VPN,
            titleRes = R.string.fix_vpn_title,
            symptomsRes = R.string.fix_vpn_symptoms,
            causeRes = R.string.fix_vpn_cause,
            fixPathRes = R.string.fix_vpn_fix,
            keywordsEn = listOf(
                "vpn", "kill switch", "kill-switch", "blocked", "disconnected", "403", "proxy",
                "socks", "orbot", "tor", "lan", "local", "router", "192.168", "always-on",
                "bypass", "start page"
            ),
            keywordsRu = listOf(
                "впн", "килсвитч", "киллсвитч", "заблокировано", "не открывается", "прокси",
                "орбот", "тор", "роутер", "локальная сеть", "стартовая страница", "туннель"
            )
        ),
        FixGuideEntry(
            id = "force_dark_and_ui",
            category = FixCategory.RENDER_UI,
            titleRes = R.string.fix_render_title,
            symptomsRes = R.string.fix_render_symptoms,
            causeRes = R.string.fix_render_cause,
            fixPathRes = R.string.fix_render_fix,
            keywordsEn = listOf(
                "dark mode", "force dark", "inverted", "contrast", "black text", "logo", "captcha",
                "zoom", "text zoom", "font size", "download", "pdf", "save file", "search engine",
                "custom search", "%s", "address bar"
            ),
            keywordsRu = listOf(
                "тёмная тема", "темная тема", "инверсия", "цвета", "чёрный текст", "контраст",
                "масштаб", "шрифт", "скачивание", "скачать", "файл", "пдф", "поисковик",
                "поиск", "адресная строка", "не качает"
            )
        ),
        FixGuideEntry(
            id = "session_autowipe_captures",
            category = FixCategory.SESSION,
            titleRes = R.string.fix_session_title,
            symptomsRes = R.string.fix_session_symptoms,
            causeRes = R.string.fix_session_cause,
            fixPathRes = R.string.fix_session_fix,
            keywordsEn = listOf(
                "logout", "logged out", "cookies", "localstorage", "session", "auto-wipe", "wipe",
                "cart", "login", "screenshot", "capture", "gallery", "hidden storage", "seed"
            ),
            keywordsRu = listOf(
                "вылетает", "разлогинивает", "аккаунт", "куки", "сессия", "очистка", "вайп",
                "корзина", "пароль", "скриншот", "галерея", "скрытое хранилище", "пропали фото"
            )
        )
    )

    fun search(
        context: Context,
        rawQuery: String,
        category: FixCategory = FixCategory.ALL
    ): List<FixGuideMatch> {
        val normalizedQuery = normalize(rawQuery)
        val tokens = tokenize(normalizedQuery)
        val pool = if (category == FixCategory.ALL) {
            entries
        } else {
            entries.filter { it.category == category }
        }

        val results = mutableListOf<FixGuideMatch>()
        for (entry in pool) {
            val title = context.getString(entry.titleRes)
            val symptoms = context.getString(entry.symptomsRes)
            val cause = context.getString(entry.causeRes)
            val fixPath = context.getString(entry.fixPathRes)

            val score = if (tokens.isEmpty()) {
                1
            } else {
                scoreEntry(
                    normalizedQuery = normalizedQuery,
                    tokens = tokens,
                    title = title,
                    symptoms = symptoms,
                    cause = cause,
                    fixPath = fixPath,
                    keywordsEn = entry.keywordsEn,
                    keywordsRu = entry.keywordsRu
                )
            }
            if (score > 0) {
                results.add(
                    FixGuideMatch(
                        entry = entry,
                        title = title,
                        symptoms = symptoms,
                        cause = cause,
                        fixPath = fixPath,
                        score = score
                    )
                )
            }
        }

        return if (tokens.isEmpty()) {
            results
        } else {
            results.sortedByDescending { it.score }
        }
    }

    internal fun scoreEntry(
        normalizedQuery: String,
        tokens: List<String>,
        title: String,
        symptoms: String,
        cause: String,
        fixPath: String,
        keywordsEn: List<String>,
        keywordsRu: List<String>
    ): Int {
        val normTitle = normalize(title)
        val titleWords = tokenize(normTitle)
        val normKeywords = (keywordsEn + keywordsRu).map { normalize(it) }
        val keywordWords = normKeywords.flatMap { tokenize(it) }
        val normBody = normalize("$symptoms $cause $fixPath")
        val bodyWords = tokenize(normBody)

        var totalScore = 0
        for (token in tokens) {
            val tokenScore = scoreToken(
                token = token,
                normTitle = normTitle,
                titleWords = titleWords,
                normKeywords = normKeywords,
                keywordWords = keywordWords,
                normBody = normBody,
                bodyWords = bodyWords
            )
            if (tokenScore == 0) {
                return 0 // AND semantics: every query token must match
            }
            totalScore += tokenScore
        }

        if (normalizedQuery.isNotEmpty()) {
            if (normTitle.contains(normalizedQuery)) totalScore += 80
            else if (normBody.contains(normalizedQuery)) totalScore += 35
        }
        return totalScore
    }

    private fun scoreToken(
        token: String,
        normTitle: String,
        titleWords: List<String>,
        normKeywords: List<String>,
        keywordWords: List<String>,
        normBody: String,
        bodyWords: List<String>
    ): Int {
        if (titleWords.any { it == token || it.startsWith(token) }) return 100
        if (normTitle.contains(token)) return 70
        if (keywordWords.any { it == token || it.startsWith(token) } ||
            normKeywords.any { it == token || it.startsWith(token) }
        ) {
            return 60
        }
        if (normKeywords.any { it.contains(token) }) return 45
        if (bodyWords.any { it == token || it.startsWith(token) } || normBody.contains(token)) return 30

        val maxDist = when {
            token.length >= 7 -> 2
            token.length >= 4 -> 1
            else -> 0
        }
        if (maxDist > 0) {
            if (titleWords.any { levenshtein(token, it) <= maxDist }) return 25
            if (keywordWords.any { levenshtein(token, it) <= maxDist }) return 20
            if (bodyWords.any { levenshtein(token, it) <= maxDist }) return 12
        }
        return 0
    }

    fun highlight(text: String, rawQuery: String, accentColor: Int): CharSequence {
        val tokens = tokenize(normalize(rawQuery)).filter { it.length >= 2 }.distinct()
        if (tokens.isEmpty()) return text

        val builder = SpannableStringBuilder(text)
        val normText = normalizeForIndexMapping(text)
        val bgColor = ColorUtils.setAlphaComponent(accentColor, 56)

        for (token in tokens) {
            var searchStart = 0
            while (searchStart < normText.length) {
                val idx = normText.indexOf(token, searchStart)
                if (idx < 0) break
                val end = (idx + token.length).coerceAtMost(builder.length)
                if (idx < end) {
                    builder.setSpan(BackgroundColorSpan(bgColor), idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(StyleSpan(Typeface.BOLD), idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                searchStart = idx + token.length
            }
        }
        return builder
    }

    internal fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input.lowercase(Locale.ROOT)) {
            when (ch) {
                'ё' -> sb.append('е')
                in 'a'..'z', in 'а'..'я', in '0'..'9', '%' -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().trim()
    }

    private fun normalizeForIndexMapping(input: String): String {
        // Preserves 1:1 character indices with the original string for accurate span highlighting.
        val sb = StringBuilder(input.length)
        for (ch in input.lowercase(Locale.ROOT)) {
            sb.append(if (ch == 'ё') 'е' else ch)
        }
        return sb.toString()
    }

    internal fun tokenize(normalized: String): List<String> {
        if (normalized.isBlank()) return emptyList()
        return normalized.split(' ').filter { it.isNotBlank() }
    }

    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        if (kotlin.math.abs(a.length - b.length) > 2) return 3

        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }
}
