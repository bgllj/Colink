package com.colink.app.data

import android.content.SharedPreferences
import com.colink.app.parsing.WeekParity
import com.colink.app.parsing.WeekRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScheduleStoreTest {

    private val sampleSchedule = StoredSchedule(
        meetings = listOf(
            StoredMeeting(
                courseCode = "1001",
                courseName = "高等数学",
                weekday = 1,
                periodStart = 1,
                periodEnd = 2,
                weekText = "1-9,11-16周单",
                weekRanges = listOf(
                    StoredWeekRange(start = 1, end = 9, parity = "ODD"),
                    StoredWeekRange(start = 11, end = 16, parity = "ODD"),
                ),
                roomText = "教A101",
            ),
            StoredMeeting(
                courseCode = null,
                courseName = "程序设计",
                weekday = 3,
                periodStart = 3,
                periodEnd = 4,
                weekText = "2-16周双",
                weekRanges = listOf(StoredWeekRange(start = 2, end = 16, parity = "EVEN")),
                roomText = null,
            ),
        ),
        semester = StoredSemester(
            startDate = "2026-08-31",
            maxWeek = 16,
            academicYear = "2025-2026",
            semesterName = "第一学期",
            className = "计科2501",
        ),
        sourceFileName = "25计科9(1).xls",
        importedAt = "2026-10-29T10:00:00Z",
    )

    @Test
    fun inMemoryStoreRoundTrips() {
        val store = InMemoryLocalScheduleStore()
        assertTrue(store.load() is ScheduleLoadResult.Empty)

        store.save(sampleSchedule)
        val loaded = store.load()
        assertTrue(loaded is ScheduleLoadResult.Ok)
        assertEquals(sampleSchedule, (loaded as ScheduleLoadResult.Ok).schedule)

        store.clear()
        assertTrue(store.load() is ScheduleLoadResult.Empty)
    }

    @Test
    fun sharedPrefsStoreRoundTripsAndKeepsWeekText() {
        val store = SharedPrefsLocalScheduleStore(FakeSharedPreferences())
        store.save(sampleSchedule)

        val loaded = store.load() as ScheduleLoadResult.Ok
        assertEquals(sampleSchedule, loaded.schedule)
        assertEquals("1-9,11-16周单", loaded.schedule.meetings[0].weekText)
        assertEquals(
            listOf(StoredWeekRange(1, 9, "ODD"), StoredWeekRange(11, 16, "ODD")),
            loaded.schedule.meetings[0].weekRanges,
        )
    }

    @Test
    fun sharedPrefsStoreReportsCorruptPayload() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("local_schedule_json", "{not json").commit()

        val store = SharedPrefsLocalScheduleStore(prefs)
        val result = store.load()
        assertTrue(result is ScheduleLoadResult.Corrupt)
    }

    @Test
    fun displayCoursesExpandParityRangesAndKeepProvenance() {
        val courses = sampleSchedule.toDisplayCourses()

        assertEquals(2, courses.size)
        assertEquals(setOf(1, 3, 5, 7, 9, 11, 13, 15), courses[0].weeks)
        assertEquals(setOf(2, 4, 6, 8, 10, 12, 14, 16), courses[1].weeks)
        assertEquals("高等数学", courses[0].name)
        assertEquals("教A101", courses[0].room)
        assertEquals("待补全", courses[1].room)
    }

    @Test
    fun storedWeekRangeConversionRoundTrips() {
        for (parity in WeekParity.entries) {
            val range = WeekRange(start = 2, end = 6, parity = parity)
            assertEquals(range, range.toStoredWeekRange().toWeekRange())
        }
    }
}

private class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clearFirst = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putStringSet(
            key: String?,
            value: MutableSet<String>?,
        ): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            pending[key!!] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearFirst = true
            return this
        }

        override fun commit(): Boolean {
            if (clearFirst) values.clear()
            for ((key, value) in pending) {
                if (value == null) values.remove(key) else values[key] = value
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
