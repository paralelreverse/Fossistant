package org.fossistant.assistant.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.net.Uri
import org.fossistant.assistant.util.AppLogger
import org.xmlpull.v1.XmlPullParser

/**
 * What the installed apps say they can do, read on the phone:
 *
 * - **Static shortcuts** (`android.app.shortcuts` on launcher activities): "New message", "Start run", ...
 * - **App Actions capabilities** (`<capability android:name="actions.intent.*">` in the same file): Google
 *   Assistant's built-in intents, with their parameters and URL templates.
 *
 * The list is rebuilt when an app is installed, updated or removed (see [invalidate]) and at least every
 * 30 minutes. It is used two ways:
 * - Gemma gets every action, grouped by app ([catalog]), in its system prompt.
 * - find_app_actions searches them by meaning when the list was cut short (a very full phone) or the
 *   short ids aren't enough to tell actions apart.
 */
class AppActionIndex(private val context: Context) {

    enum class Source { CAPABILITY, SHORTCUT }

    data class Param(val name: String, val key: String = name)

    data class AppAction(
        /** `sc:pkg/shortcutId` or `cap:pkg/actions.intent.NAME`. */
        val id: String,
        val packageName: String,
        val appLabel: String,
        val label: String,
        val source: Source,
        val params: List<Param> = emptyList(),
        val description: String = "",
        val intent: Intent? = null,
        /** App Actions URL template, e.g. `myapp://run{?exerciseType}`. */
        val urlTemplate: String? = null,
        /** Short id Gemma uses, e.g. `keep.new_note` (app name + label, letters, digits and `_` only). */
        val key: String = ""
    ) {
        val searchText: String by lazy { "$appLabel $label $description ${params.joinToString(" ") { it.name }}".lowercase() }
        /** Pays, sends, deletes, ...: only after the user confirms. */
        val isSensitive: Boolean by lazy { SENSITIVE.containsMatchIn("$label $description") }
    }

    @Volatile private var cache: List<AppAction> = emptyList()
    @Volatile private var builtAtMs = 0L

    /** Forget the list; the next [actions] call reads the apps again (called when an app is (un)installed). */
    fun invalidate() {
        builtAtMs = 0L
    }

    /** Every action, rebuilt after [invalidate] or after [REFRESH_MS]. */
    @Synchronized
    fun actions(): List<AppAction> {
        val now = System.currentTimeMillis()
        if (builtAtMs == 0L || now - builtAtMs > REFRESH_MS) {
            cache = runCatching { build() }.getOrElse {
                AppLogger.w(TAG, "Couldn't index app actions: ${it.message}", it)
                cache
            }
            builtAtMs = now
            AppLogger.i(TAG, "Indexed ${cache.size} app actions (${cache.count { it.source == Source.CAPABILITY }} App Actions)")
        }
        return cache
    }

    /** By short id (`google_keep.new_note`), full id (`sc:pkg/id`), or a near miss Gemma makes with the short id. */
    fun byId(id: String): AppAction? = byId(actions(), id)

    /** Every action for Gemma's system prompt, cut only at [maxChars] (a safety limit for the context window). */
    fun catalog(maxChars: Int = Int.MAX_VALUE): String = catalog(actions(), maxChars)

    /** The [limit] actions that best match [query] (words of the request, optionally the app's name). */
    fun search(query: String, limit: Int = 6): List<AppAction> {
        val words = tokens(query)
        if (words.isEmpty()) return emptyList()
        return actions()
            .map { action -> action to score(action, words) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<AppAction, Int>> { it.second }.thenBy { it.first.source.ordinal })
            .take(limit)
            .map { it.first }
    }

    // ---- Building ----

    private fun build(): List<AppAction> {
        val pm = context.packageManager
        val labels = HashMap<String, String>()
        fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
        return withKeys(
            readShortcutsAndCapabilities(pm, ::appLabel)
                .distinctBy { it.id }
                .filter { it.packageName != context.packageName }
        )
    }

    private fun readShortcutsAndCapabilities(pm: PackageManager, appLabel: (String) -> String): List<AppAction> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = runCatching { pm.queryIntentActivities(launcher, PackageManager.GET_META_DATA) }.getOrDefault(emptyList())
        val out = ArrayList<AppAction>()
        for (info in activities) {
            val activity = info.activityInfo ?: continue
            val parser = runCatching { activity.loadXmlMetaData(pm, SHORTCUTS_META) }.getOrNull() ?: continue
            val res = runCatching { pm.getResourcesForApplication(activity.applicationInfo) }.getOrNull()
            try {
                out += parseShortcutsXml(parser, res, activity.packageName, appLabel(activity.packageName))
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Bad shortcuts.xml in ${activity.packageName}: ${t.message}")
            } finally {
                parser.close()
            }
        }
        return out
    }

    private fun parseShortcutsXml(parser: XmlResourceParser, res: Resources?, pkg: String, appLabel: String): List<AppAction> {
        val out = ArrayList<AppAction>()
        var shortcutId: String? = null
        var shortcutLabel: String? = null
        var shortcutEnabled = true
        var shortcutIntent: Intent? = null
        var capability: String? = null
        var capIntent: Intent? = null
        var capTemplate: String? = null
        val capParams = ArrayList<Param>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "shortcut" -> {
                        shortcutId = attr(parser, "shortcutId")
                        shortcutLabel = label(parser, res, "shortcutLongLabel") ?: label(parser, res, "shortcutShortLabel")
                        shortcutEnabled = attr(parser, "enabled")?.lowercase() != "false"
                        shortcutIntent = null
                    }
                    "capability" -> {
                        capability = attr(parser, "name")
                        capIntent = null; capTemplate = null; capParams.clear()
                    }
                    "intent" -> {
                        val intent = Intent(attr(parser, "action") ?: Intent.ACTION_VIEW).apply {
                            val target = attr(parser, "targetPackage") ?: pkg
                            attr(parser, "targetClass")?.let { setComponent(ComponentName(target, it)) } ?: setPackage(target)
                            attr(parser, "data")?.let { data = Uri.parse(it) }
                        }
                        if (capability != null && shortcutId == null) capIntent = capIntent ?: intent
                        else if (shortcutId != null) shortcutIntent = shortcutIntent ?: intent
                    }
                    "url-template" -> capTemplate = attr(parser, "value")
                    "parameter" -> if (capability != null) {
                        val name = attr(parser, "name")
                        if (name != null) capParams += Param(name.replace('.', '_'), attr(parser, "key") ?: name)
                    }
                    "categories", "capability-binding", "extra", "data", "slice" -> {}
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "shortcut" -> {
                        val id = shortcutId
                        val intent = shortcutIntent
                        if (id != null && intent != null && shortcutEnabled) {
                            out += AppAction(
                                id = "sc:$pkg/$id",
                                packageName = pkg,
                                appLabel = appLabel,
                                label = shortcutLabel ?: humanize(id),
                                source = Source.SHORTCUT,
                                intent = intent
                            )
                        }
                        shortcutId = null
                    }
                    "capability" -> {
                        val name = capability
                        if (name != null && (capIntent != null || capTemplate != null)) {
                            out += AppAction(
                                id = "cap:$pkg/$name",
                                packageName = pkg,
                                appLabel = appLabel,
                                label = humanize(name.substringAfterLast('.')),
                                source = Source.CAPABILITY,
                                params = capParams.toList(),
                                intent = capIntent,
                                urlTemplate = capTemplate
                            )
                        }
                        capability = null
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    // ---- Matching ----

    private fun score(action: AppAction, words: Set<String>): Int {
        val app = action.appLabel.lowercase()
        var score = 0
        for (word in words) {
            if (app.contains(word)) score += 3
            else if (action.searchText.contains(word)) score += 2
        }
        return score
    }

    private fun tokens(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in STOP_WORDS }.toSet()

    private fun attr(parser: XmlResourceParser, name: String): String? =
        parser.getAttributeValue(ANDROID_NS, name) ?: parser.getAttributeValue(null, name)

    private fun label(parser: XmlResourceParser, res: Resources?, name: String): String? {
        val resId = parser.getAttributeResourceValue(ANDROID_NS, name, 0)
        if (resId != 0 && res != null) return runCatching { res.getString(resId) }.getOrNull()
        return attr(parser, name)?.takeIf { !it.startsWith("@") }
    }

    companion object {
        private const val TAG = "AppActionIndex"
        private const val REFRESH_MS = 30 * 60_000L
        private val SENSITIVE = Regex(
            "(?i)\\b(pay|buy|purchase|order|checkout|send|transfer|delete|remove|uninstall|install|confirm|subscribe|" +
                "sign out|log ?out|reset|erase|format|accept|allow|grant|share|post|publish|call)\\b"
        )
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val SHORTCUTS_META = "android.app.shortcuts"
        private val STOP_WORDS = setOf("the", "and", "for", "with", "app", "please", "can", "you", "new", "open", "use", "using", "into", "from", "my")

        /** `Google Keep` + `New note` -> `google_keep.new_note`; letters (any language), digits and `_` only. */
        fun slug(text: String, max: Int = 28): String =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString("_").take(max).trimEnd('_')

        /** Gives every action a unique short [AppAction.key]. */
        fun withKeys(actions: List<AppAction>): List<AppAction> {
            val used = HashSet<String>()
            return actions.map { action ->
                val base = "${slug(action.appLabel, 20).ifEmpty { "app" }}.${slug(action.label).ifEmpty { "action" }}"
                var key = base
                var n = 2
                while (!used.add(key)) key = "${base}_${n++}"
                action.copy(key = key)
            }
        }

        /**
         * Every action grouped by app, apps in name order, App Actions first:
         * `google_keep: new_note, new_list; strava: start_exercise(exercise_type), record`. The id Gemma passes is
         * `app.action` (`google_keep.new_note`). Only an app that would go past [maxChars] is left out (with "...").
         */
        fun catalog(actions: List<AppAction>, maxChars: Int): String {
            val out = StringBuilder()
            val apps = actions.groupBy { it.key.substringBefore('.') }.toSortedMap()
            for ((app, appActions) in apps) {
                val entry = app + ": " + appActions.sortedBy { it.source.ordinal }.joinToString(", ") { a ->
                    val name = a.key.substringAfter('.')
                    if (a.params.isEmpty()) name else "$name(${a.params.joinToString(",") { it.name }})"
                }
                if (out.length + entry.length + 2 > maxChars) {
                    out.append("; ...")
                    break
                }
                if (out.isNotEmpty()) out.append("; ")
                out.append(entry)
            }
            return out.toString()
        }

        /**
         * Exact short or full id first; then forgiving matches for what a small model gets wrong: `-` or spaces
         * for `_`, or the app part shortened (`keep.new_note` for `google_keep.new_note`) when that is unique.
         */
        fun byId(actions: List<AppAction>, id: String): AppAction? {
            val wanted = id.trim().trim('\'', '"', '`')
            actions.firstOrNull { it.key.equals(wanted, true) || it.id.equals(wanted, true) }?.let { return it }
            val norm = wanted.lowercase().replace(Regex("[\\s-]+"), "_")
            actions.firstOrNull { it.key == norm }?.let { return it }
            val app = norm.substringBefore('.', "")
            val action = norm.substringAfter('.')
            if (app.isEmpty() || action.isEmpty()) return null
            val candidates = actions.filter { a ->
                val keyApp = a.key.substringBefore('.')
                a.key.substringAfter('.') == action && (keyApp.endsWith(app) || keyApp.startsWith(app) || keyApp.contains(app))
            }
            return candidates.singleOrNull()
        }

        /** `createNote` / `START_EXERCISE` / `compose_message` -> "create note" / "start exercise" / "compose message". */
        fun humanize(raw: String): String =
            raw.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ').replace('-', ' ').trim().lowercase()

        private fun encode(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /** Expands `{?a,b}` and `{a}` in an App Actions URL template with [values]. */
        fun expandTemplate(template: String, values: Map<String, String>): String =
            Regex("\\{([?&]?)([^}]*)\\}").replace(template) { m ->
                val op = m.groupValues[1]
                val keys = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (op.isEmpty()) {
                    keys.firstNotNullOfOrNull { values[it] }?.let(::encode).orEmpty()
                } else {
                    val pairs = keys.mapNotNull { k -> values[k]?.let { "$k=${encode(it)}" } }
                    if (pairs.isEmpty()) "" else op + pairs.joinToString("&")
                }
            }
    }
}
