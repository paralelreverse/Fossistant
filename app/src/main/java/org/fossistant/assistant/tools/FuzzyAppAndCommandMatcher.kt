package org.fossistant.assistant.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import kotlin.math.max
import kotlin.math.min

/**
 * Intelligent phonetic autocorrect and fuzzy application search matcher.
 * Compensates for Whisper ASR mishearings (e.g. "open fast assistent" -> "open Fossistant",
 * "spotfy" -> "Spotify", "u tube" -> "YouTube") using Levenshtein distance and token similarity.
 */
object FuzzyAppAndCommandMatcher {

    /**
     * Common phonetic and colloquial autocorrect substitutions for Whisper transcripts.
     */
    private val phoneticSubstitutions = listOf(
        Regex("(?i)\\b(fast\\s+assistent|fast\\s+assistant|foss\\s+assistant|fossasistent|foss\\s+assistent)\\b") to "fossistant",
        Regex("(?i)\\b(opem|opne|oepn|oppen)\\b") to "open",
        Regex("(?i)\\b(phptos|fotoss?|photoss?)\\b") to "photos",
        Regex("(?i)\\b(gallary|galery)\\b") to "gallery",
        Regex("(?i)\\b(settins|setings)\\b") to "settings",
        Regex("(?i)\\b(messag(es)?|msg)\\b") to "messages",
        Regex("(?i)\\b(camer[ao]|cam)\\b") to "camera",
        Regex("(?i)\\b(calculater|calc)\\b") to "calculator",
        Regex("(?i)\\b(contacs|contatcs)\\b") to "contacts",
        Regex("(?i)\\b(calender|calandar)\\b") to "calendar",
        Regex("(?i)\\b(spot\\s*if\\s*y|spot\\s+if\\s+i|spotfy)\\b") to "spotify",
        Regex("(?i)\\b(u\\s+tube|you\\s+tube)\\b") to "youtube",
        Regex("(?i)\\b(whats\\s+app|what\\s+app)\\b") to "whatsapp",
        Regex("(?i)\\b(insta\\s+gram)\\b") to "instagram",
        Regex("(?i)\\b(torch|flash\\s*lite|flash\\s+light)\\b") to "flashlight",
        Regex("(?i)\\bosm\\s*&|\\bosm\\s+and\\b") to "osmand",
        Regex("(?i)\\b(shoot\\s+a\\s+text\\s+to|send\\s+a\\s+message\\s+to)\\b") to "send text to",
        Regex("(?i)\\b(give\\s+([a-zA-Z0-9]+)\\s+a\\s+call)\\b") to "call $2",
        Regex("(?i)\\b(drop\\s+([a-zA-Z0-9]+)\\s+a\\s+call)\\b") to "call $2"
    )

    /**
     * Cross-lingual application name translations (e.g. speaking in Slovak, Czech, or Polish
     * while the phone launcher has apps in English). Applied during app name sanitization and matching.
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
     * Normalizes and autocorrects a raw speech transcript before routing.
     */
    fun autocorrectTranscript(rawTranscript: String): String {
        var corrected = rawTranscript.trim()
        for ((regex, replacement) in phoneticSubstitutions) {
            corrected = regex.replace(corrected, replacement)
        }
        return corrected
    }

    /**
     * Normalizes an app name search query by stripping common command verbs,
     * articles, suffixes like "app" or "application", punctuation, and translating
     * cross-lingual Slavic app nouns to English canonical names.
     */
    fun sanitizeAppQuery(query: String): String {
        var q = autocorrectTranscript(query).trim().lowercase()
        // Strip leading command verbs in English, Slovak, Czech, Polish, Russian, Ukrainian
        q = q.replace(Regex("^(?:please\\s+)?(?:open|opem|opne|oepn|oppen|launch|start|run|bring up|show|go to|otvor(?:te)?|spusti(?:ť)?|zapni|otevři|otevri|otwórz|otworz|uruchom|odpal|открой(?:те)?|запусти(?:те)?|відкрий(?:те)?)\\s+"), "")
        // Strip leading articles or application descriptors
        q = q.replace(Regex("^(?:the|my|a|an|aplikáci[au]|aplikaci[au]|aplikace|aplikac[jęe]|приложение|додаток)\\s+"), "")
        // Strip trailing punctuation
        q = q.replace(Regex("[\\p{Punct}\\s]+$"), "").trim()
        // Strip trailing "app" or "application" or "apps" or Slavic variants
        q = q.replace(Regex("\\s+(?:app|application|apps|aplikácia|aplikáciu|aplikacja|aplikację|приложение)$"), "")
        // Strip trailing punctuation again
        q = q.replace(Regex("[\\p{Punct}\\s]+$"), "").trim()

        // Translate cross-lingual app nouns to English so Slavic requests match English launcher labels
        for ((regex, replacement) in crossLingualAppTranslations) {
            q = regex.replace(q, replacement)
        }
        return q
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
     * Resolves the best matching installed Android application package for a given search query,
     * using exact matching, alias lookup, prefix matching, token similarity, and fuzzy edit distance.
     */
    fun findBestMatchingApp(context: Context?, query: String): ResolveInfo? {
        if (context == null || query.isBlank()) return null
        val pm = context.packageManager ?: return null
        val cleanQuery = autocorrectTranscript(query).trim().lowercase()
        val sanitizedQuery = sanitizeAppQuery(query).ifBlank { cleanQuery }

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val installedApps = runCatching {
            pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        }.getOrNull().orEmpty()

        if (installedApps.isEmpty()) return null

        // Resolve each launcher activity's label once so we don't repeat IPC calls across the 3 matching passes
        val labeledApps = installedApps.map { app ->
            val label = runCatching { app.loadLabel(pm).toString().trim().lowercase() }.getOrDefault("")
            val pkg = app.activityInfo.packageName.lowercase()
            Triple(app, label, pkg)
        }

        // 1. Exact match against app label or package
        for ((app, label, pkg) in labeledApps) {
            if (label == cleanQuery || pkg == cleanQuery || label == sanitizedQuery || pkg == sanitizedQuery) {
                return app
            }
        }

        // 2. StartsWith or Contains match
        for ((app, label, _) in labeledApps) {
            if (label.isNotEmpty() && (
                    label.startsWith(sanitizedQuery) || sanitizedQuery.startsWith(label) ||
                        label.contains(sanitizedQuery) || sanitizedQuery.contains(label) ||
                        label.startsWith(cleanQuery) || cleanQuery.startsWith(label) || label.contains(cleanQuery)
                    )
            ) {
                return app
            }
        }

        // 3. Fuzzy edit distance & token-level search across all installed app labels
        var bestApp: ResolveInfo? = null
        var highestScore = 0.52f // Minimum similarity threshold to capture typos like "phptos" (0.83)

        for ((app, label, _) in labeledApps) {
            if (label.isEmpty()) continue
            val score = max(similarityScore(cleanQuery, label), similarityScore(sanitizedQuery, label))

            // Token-level matching (e.g. "Photos" in "Google Photos" vs "phptos")
            val tokenScore = label.split(Regex("[^a-zA-Z0-9]+"))
                .filter { it.length >= 3 }
                .maxOfOrNull { similarityScore(sanitizedQuery, it) } ?: 0f

            val strippedQuery = sanitizedQuery.replace(Regex("\\s+"), "")
            val strippedLabel = label.replace(Regex("\\s+"), "")
            val strippedScore = similarityScore(strippedQuery, strippedLabel)
            val effectiveScore = max(max(score, strippedScore), tokenScore)

            if (effectiveScore > highestScore) {
                highestScore = effectiveScore
                bestApp = app
            }
        }

        return bestApp
    }

    data class AppMatchCandidate(val label: String, val packageName: String)

    /**
     * Finds the best matching candidate from a provided list of app names/packages without Android Context.
     */
    fun findBestMatchCandidate(query: String, candidates: List<AppMatchCandidate>): AppMatchCandidate? {
        val cleanQuery = autocorrectTranscript(query).trim().lowercase()
        val sanitizedQuery = sanitizeAppQuery(query).ifBlank { cleanQuery }

        // 1. Exact match
        for (c in candidates) {
            val label = c.label.trim().lowercase()
            val pkg = c.packageName.lowercase()
            if (label == cleanQuery || pkg == cleanQuery || label == sanitizedQuery || pkg == sanitizedQuery) return c
        }
        // 2. StartsWith or Contains match
        for (c in candidates) {
            val label = c.label.trim().lowercase()
            if (label.startsWith(sanitizedQuery) || sanitizedQuery.startsWith(label) ||
                label.contains(sanitizedQuery) || sanitizedQuery.contains(label) ||
                label.startsWith(cleanQuery) || cleanQuery.startsWith(label) || label.contains(cleanQuery)
            ) return c
        }
        // 3. Fuzzy edit distance & token search
        var best: AppMatchCandidate? = null
        var highestScore = 0.52f
        for (c in candidates) {
            val label = c.label.trim().lowercase()
            val score = max(similarityScore(cleanQuery, label), similarityScore(sanitizedQuery, label))

            val tokenScore = label.split(Regex("[^a-zA-Z0-9]+"))
                .filter { it.length >= 3 }
                .maxOfOrNull { similarityScore(sanitizedQuery, it) } ?: 0f

            val strippedQuery = sanitizedQuery.replace(Regex("\\s+"), "")
            val strippedLabel = label.replace(Regex("\\s+"), "")
            val strippedScore = similarityScore(strippedQuery, strippedLabel)
            val effectiveScore = max(max(score, strippedScore), tokenScore)

            if (effectiveScore > highestScore) {
                highestScore = effectiveScore
                best = c
            }
        }
        return best
    }
}
