package org.fossistant.assistant

import org.fossistant.assistant.tools.AppActionIndex
import org.fossistant.assistant.tools.AppActionIndex.AppAction
import org.fossistant.assistant.tools.AppActionIndex.Param
import org.fossistant.assistant.tools.AppActionIndex.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActionIndexTest {

    private fun action(pkg: String, app: String, label: String, source: Source = Source.SHORTCUT, params: List<Param> = emptyList()) =
        AppAction(id = "sc:$pkg/${label.replace(' ', '_')}", packageName = pkg, appLabel = app, label = label, source = source, params = params)

    private val actions = AppActionIndex.withKeys(
        listOf(
            action("com.google.android.keep", "Google Keep", "New note"),
            action("com.google.android.keep", "Google Keep", "New list"),
            action("com.strava", "Strava", "start exercise", Source.CAPABILITY, listOf(Param("exercise_type", "exerciseType"))),
            action("com.strava", "Strava", "Record"),
            action("org.bank", "My Bank", "Send money"),
            action("com.whatsapp", "WhatsApp", "Camera")
        )
    )

    @Test
    fun humanizesIdsAndBuiltInIntents() {
        assertEquals("create note", AppActionIndex.humanize("createNote"))
        assertEquals("start exercise", AppActionIndex.humanize("START_EXERCISE"))
        assertEquals("compose message", AppActionIndex.humanize("compose_message"))
    }

    @Test
    fun expandsAppActionsUrlTemplates() {
        assertEquals(
            "myapp://run?exerciseType=Running&distance=5%20km",
            AppActionIndex.expandTemplate("myapp://run{?exerciseType,distance}", mapOf("exerciseType" to "Running", "distance" to "5 km"))
        )
        assertEquals("myapp://run", AppActionIndex.expandTemplate("myapp://run{?exerciseType}", emptyMap()))
        assertEquals("https://x.app/search/milk", AppActionIndex.expandTemplate("https://x.app/search/{q}", mapOf("q" to "milk")))
    }

    @Test
    fun givesShortUniqueKeys() {
        assertEquals("google_keep.new_note", actions[0].key)
        assertEquals("strava.start_exercise", actions[2].key)
        val twice = AppActionIndex.withKeys(listOf(action("a", "Notes", "New"), action("b", "Notes", "New")))
        assertEquals(listOf("notes.new", "notes.new_2"), twice.map { it.key })
        // markup or instructions in an app's label can't reach Gemma's prompt as anything but a plain word id
        assertEquals("b_b.ignore_previous", AppActionIndex.withKeys(listOf(action("p", "<b>!</b>", "Ignore previous!")))[0].key)
    }

    @Test
    fun catalogListsEveryActionGroupedByApp() {
        val catalog = AppActionIndex.catalog(actions, Int.MAX_VALUE)
        assertEquals(
            "google_keep: new_note, new_list; my_bank: send_money; strava: start_exercise(exercise_type), record; whatsapp: camera",
            catalog
        )
        // only the safety limit cuts it, and only between apps
        val short = AppActionIndex.catalog(actions, 60)
        assertEquals("google_keep: new_note, new_list; my_bank: send_money; ...", short)
    }

    @Test
    fun findsActionsByTheIdsGemmaWrites() {
        assertEquals("google_keep.new_note", AppActionIndex.byId(actions, "google_keep.new_note")?.key)
        assertEquals("google_keep.new_note", AppActionIndex.byId(actions, "sc:com.google.android.keep/New_note")?.key)
        assertEquals("google_keep.new_note", AppActionIndex.byId(actions, "'google-keep.new note'")?.key)
        assertEquals("google_keep.new_note", AppActionIndex.byId(actions, "keep.new_note")?.key)
        assertEquals("strava.record", AppActionIndex.byId(actions, "strava.record")?.key)
        assertEquals(null, AppActionIndex.byId(actions, "keep.delete_everything"))
        assertEquals(null, AppActionIndex.byId(actions, "new_note"))
    }
}
