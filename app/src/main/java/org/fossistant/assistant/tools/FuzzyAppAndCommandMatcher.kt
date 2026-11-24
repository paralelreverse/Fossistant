package org.fossistant.assistant.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import kotlin.math.max
import kotlin.math.min

/**
 * Phonetic wake-word / app-name normalization and conservative fuzzy application lookup.
 */
object FuzzyAppAndCommandMatcher {

    private const val APP_CACHE_TTL_MS = 60_000L
    private const val MIN_FUZZY_SIMILARITY = 0.72f

    private val LEADING_COMMAND_VERB_REGEX =
        Regex("^(?:please\\s+)?(?:open|opem|opne|oepn|oppen|launch|start|run|bring up|show|go to|otvor(?:te)?|spusti(?:ť)?|zapni|otevři|otevri|otwórz|otworz|uruchom|odpal|открой(?:те)?|запусти(?:те)?|відкрий(?:те)?)\\s+")
    private val LEADING_ARTICLE_REGEX =
        Regex("^(?:the|my|a|an|aplikáci[au]|aplikaci[au]|aplikace|aplikac[jęe]|приложение|додаток)\\s+")
    private val TRAILING_PUNCTUATION_REGEX = Regex("[\\p{Punct}\\s]+$")
    private val TRAILING_APP_WORD_REGEX =
        Regex("\\s+(?:app|application|apps|aplikácia|aplikáciu|aplikacja|aplikację|приложение)$")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val NON_ALPHANUM_REGEX = Regex("[^\\p{L}\\p{N}]+")

    private data class CachedLauncherApp(
        val resolveInfo: ResolveInfo,
        val label: String,
        val pkg: String
    )

    private data class LauncherCacheSnapshot(
        val apps: List<CachedLauncherApp>,
        val cachedAtMs: Long,
        val pmIdentity: Int
    )

    @Volatile
    private var launcherCache: LauncherCacheSnapshot? = null

    fun invalidateAppCache() {
        launcherCache = null
    }

    private fun getLauncherApps(pm: PackageManager): List<CachedLauncherApp> {
        val now = System.currentTimeMillis()
        val pmId = System.identityHashCode(pm)
        val cached = launcherCache
        if (cached != null && cached.pmIdentity == pmId && now - cached.cachedAtMs < APP_CACHE_TTL_MS) {
            return cached.apps
        }
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val installedApps = runCatching {
            pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        }.getOrNull().orEmpty()
        val labeledApps = installedApps.map { app ->
            val label = runCatching { app.loadLabel(pm).toString().trim().lowercase() }.getOrDefault("")
            val pkg = app.activityInfo.packageName.lowercase()
            CachedLauncherApp(app, label, pkg)
        }
        if (labeledApps.isNotEmpty()) {
            launcherCache = LauncherCacheSnapshot(labeledApps, System.currentTimeMillis(), pmId)
        }
        return labeledApps
    }

    /**
     * Unambiguous multi-word phonetic corrections for wake-word detection and explicit app lookups.
     */
    private val PHONETIC_APP_CORRECTIONS = listOf(
        Regex("(?i)\\b(fast\\s+assistent|fast\\s+assistant|foss\\s+assistant|fossasistent|foss\\s+assistent)\\b") to "fossistant",
        Regex("(?i)\\b(spot\\s*if\\s*y|spot\\s+if\\s+i|spotfy)\\b") to "spotify",
        Regex("(?i)\\b(u\\s+tube|you\\s+tube)\\b") to "youtube",
        Regex("(?i)\\bosm\\s*&|\\bosm\\s+and\\b") to "osmand",
        Regex("(?i)\\b(phptos|fotoss?|photoss?)\\b") to "photos",
        Regex("(?i)\\b(gallary|galery)\\b") to "gallery",
        Regex("(?i)\\b(settins|setings)\\b") to "settings",
        Regex("(?i)\\b(contacs|contatcs)\\b") to "contacts",
        Regex("(?i)\\bcalandar\\b") to "calendar",
        Regex("(?i)\\bcalculater\\b") to "calculator",
        Regex("(?i)\\bwhats\\s+app\\b") to "whatsapp",
        Regex("(?i)\\binsta\\s+gram\\b") to "instagram"
    )

    /**
     * Cross-lingual application name translations (e.g. speaking in Slovak, Czech, or Polish
     * while the phone launcher has apps in English). Applied only during explicit app-name lookup.
     */
    private val crossLingualAppTranslations = listOf(
        Regex("(?i)\\b(galéri[au]|galeri[au]|galérie|galerie)\\b") to "gallery",
        Regex("(?i)\\b(fotky|fotografie|zdjęcia|zdjecia|fotiek|fotografi[eí])\\b") to "photos",
        Regex("(?i)\\b(nastaveni[ae]|nastavenia|ustawienia|настройки|налаштування)\\b") to "settings",
        Regex("(?i)\\b(fotoaparát|fotoaparat|aparat|камера|фотоаппарат)\\b") to "camera",
        Regex("(?i)\\b(kalkulačk[au]|kalkulack[au]|kalkulačka|kalkulator|калькулятор)\\b") to "calculator",
        Regex("(?i)\\b(hodin[ky]|hodiniek|budík|budik|zegar|zegarek|часы|годинник)\\b") to "clock",
        Regex("(?i)\\b(správ[ya]|sprav[ya]|správy|wiadomości|wiadomosci|сообщения|повідомлення)\\b") to "messages",
        Regex("(?i)\\b(kontakt[y]|kontaktoch|контакты|контакти)\\b") to "contacts",
        Regex("(?i)\\b(kalendár|kalendar|kalendarz|календарь|календар)\\b") to "calendar",
        Regex("(?i)\\b(hudb[au]|hudba|muzyka|музыка|музика)\\b") to "music",
        Regex("(?i)\\b(telefón|telefon|slúchadlo|телефон)\\b") to "phone",
        Regex("(?i)\\b(map[ya]|mapy|nawigacja|навигация|навігація)\\b") to "maps",
        Regex("(?i)\\b(súbor[y]|subor[y]|pliki|файлы|файли)\\b") to "files",
        Regex("(?i)\\b(poznámk[y]|poznamk[y]|notatki|заметки|нотатки)\\b") to "notes",
        Regex("(?i)\\b(prehliadač|prehliadac|przeglądarka|przegladarka|браузер)\\b") to "browser"
    )

    /**
     * Applies unambiguous phonetic wake-word / app-name corrections without rewriting normal words or sentences.
     */
    fun autocorrectTranscript(rawTranscript: String): String {
        var corrected = rawTranscript.trim()
        for ((regex, replacement) in PHONETIC_APP_CORRECTIONS) {
            corrected = regex.replace(corrected, replacement)
        }
        return corrected
    }

    /**
     * Normalizes an explicit app name query by stripping command verbs, articles, trailing "app" suffix,
     * applying phonetic app-name fixes, and translating common cross-lingual app nouns to English.
     */
    fun sanitizeAppQuery(query: String): String {
        var q = autocorrectTranscript(query).trim().lowercase()
        q = q.replace(LEADING_COMMAND_VERB_REGEX, "")
        q = q.replace(LEADING_ARTICLE_REGEX, "")
        q = q.replace(TRAILING_PUNCTUATION_REGEX, "").trim()
        q = q.replace(TRAILING_APP_WORD_REGEX, "")
        q = q.replace(TRAILING_PUNCTUATION_REGEX, "").trim()

        for ((regex, replacement) in crossLingualAppTranslations) {
            q = regex.replace(q, replacement)
        }
        return q.replace(WHITESPACE_REGEX, " ").trim()
    }

    /**
     * Computes the Levenshtein edit distance between two strings.
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        val a = s1.lowercase()
        val b = s2.lowercase()
        val costs = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            costs[0] = i
            var nw = i - 1
            for (j in 1..b.length) {
                val cj = min(1 + min(costs[j], costs[j - 1]), if (a[i - 1] == b[j - 1]) nw else nw + 1)
                nw = costs[j]
                costs[j] = cj
            }
        }
        return costs[b.length]
    }

    /**
     * Computes similarity score between 0.0 (completely different) and 1.0 (identical).
     */
    fun similarityScore(s1: String, s2: String): Float {
        val a = s1.trim().lowercase()
        val b = s2.trim().lowercase()
        if (a == b) return 1.0f
        if (a.isEmpty() || b.isEmpty()) return 0.0f
        val maxLen = max(a.length, b.length)
        val dist = levenshteinDistance(a, b)
        return (1.0f - (dist.toFloat() / maxLen.toFloat())).coerceIn(0f, 1f)
    }

    /**
     * Resolves the best matching installed Android application package for an explicit app name query.
     */
    fun findBestMatchingApp(context: Context?, query: String): ResolveInfo? {
        if (context == null || query.isBlank()) return null
        val pm = context.packageManager ?: return null
        val labeledApps = getLauncherApps(pm)
        if (labeledApps.isEmpty()) return null
        return matchAppCandidate(query, labeledApps, { it.label }, { it.pkg })?.resolveInfo
    }

    data class AppMatchCandidate(val label: String, val packageName: String)

    /**
     * Finds the best matching candidate from a provided list of app names/packages without Android Context.
     */
    fun findBestMatchCandidate(query: String, candidates: List<AppMatchCandidate>): AppMatchCandidate? =
        matchAppCandidate(query, candidates, { it.label }, { it.packageName })

    private fun <T> matchAppCandidate(
        query: String,
        candidates: List<T>,
        labelOf: (T) -> String,
        packageOf: (T) -> String
    ): T? {
        val sanitizedQuery = sanitizeAppQuery(query)
        if (sanitizedQuery.length < 2 || candidates.isEmpty()) return null

        // 1. Exact match against app label, package name, or package suffix.
        for (candidate in candidates) {
            val label = labelOf(candidate).trim().lowercase()
            val pkg = packageOf(candidate).trim().lowercase()
            val pkgSuffix = pkg.substringAfterLast('.')
            if (label == sanitizedQuery || pkg == sanitizedQuery || pkgSuffix == sanitizedQuery) {
                return candidate
            }
        }

        // 2. Prefix or whole-word match for queries >= 3 chars (never arbitrary sanitizedQuery.contains(label)).
        if (sanitizedQuery.length >= 3) {
            for (candidate in candidates) {
                val label = labelOf(candidate).trim().lowercase()
                if (label.isEmpty()) continue
                val words = label.split(NON_ALPHANUM_REGEX).filter { it.isNotEmpty() }
                if (label.startsWith(sanitizedQuery) || words.any { it == sanitizedQuery }) {
                    return candidate
                }
            }
        }

        // 3. Conservative typo tolerance against full label and label words >= 4 chars.
        var best: T? = null
        var highestScore = 0f
        val strippedQuery = sanitizedQuery.replace(WHITESPACE_REGEX, "")

        for (candidate in candidates) {
            val label = labelOf(candidate).trim().lowercase()
            if (label.isEmpty()) continue

            val labelScore = similarityScore(sanitizedQuery, label)
            val strippedScore = similarityScore(strippedQuery, label.replace(WHITESPACE_REGEX, ""))
            val wordScore = label.split(NON_ALPHANUM_REGEX)
                .filter { it.length >= 4 }
                .maxOfOrNull { similarityScore(sanitizedQuery, it) } ?: 0f

            val effectiveScore = max(max(labelScore, strippedScore), wordScore)
            if (effectiveScore >= MIN_FUZZY_SIMILARITY && effectiveScore > highestScore) {
                highestScore = effectiveScore
                best = candidate
            }
        }

        return best
    }
}
