package com.colink.app.parsing

import com.colink.app.parsing.profiles.extractChengduWenliV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class SampleWorkbookTest {

    private fun findSamplesDir(): File? {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "samples")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private fun sampleFiles(): List<File> {
        val samples = findSamplesDir() ?: return emptyList()
        return samples.walkTopDown()
            .filter { it.isFile && (it.extension.equals("xls", true) || it.extension.equals("xlsx", true)) }
            .toList()
    }

    private fun knownSample(): File? =
        sampleFiles().firstOrNull { it.name == "25计科9(1).xls" }

    @Test
    fun anySampleWorkbookReadsAndExtractsWithoutError() {
        val files = sampleFiles().filter { detectFormat(it.readBytes()) != null }
        assumeTrue("本地样例课表未提供（samples/ 不入库）", files.isNotEmpty())
        for (file in files) {
            val workbook = readWorkbook(file.readBytes())
            assertTrue(workbook.sheets.isNotEmpty())
            extractChengduWenliV1(workbook)
        }
    }

    @Test
    fun knownSampleMetadata() {
        val sample = knownSample()
        assumeTrue("本地样例课表未提供（samples/excel 不入库）", sample != null)
        val result = extractChengduWenliV1(readWorkbook(sample!!.readBytes()))
        val meta = result.meta
        assertEquals("2026-2027", meta.academicYear)
        assertEquals("第一学期", meta.semesterName)
        assertEquals(LocalDate.of(2026, 8, 31), meta.startDate)
        assertEquals("成都文理学院2026-2027学年第一学期课表", meta.title)
        assertTrue(meta.className != null && meta.className!!.contains("计算机科学与技术9"))
        assertEquals("人工智能与大数据学院", meta.department)
        assertEquals("2025", meta.grade)
        assertEquals("计算机科学与技术", meta.major)
    }

    @Test
    fun knownSamplePeCourseAndProvenance() {
        val sample = knownSample()
        assumeTrue("本地样例课表未提供（samples/excel 不入库）", sample != null)
        val result = extractChengduWenliV1(readWorkbook(sample!!.readBytes()))
        assertTrue(result.occurrences.isNotEmpty())
        val pe = result.occurrences.filter { it.courseCode == "193900" }
        assertEquals(1, pe.size)
        val sport = pe[0]
        assertEquals(1, sport.weekday)
        assertEquals(1, sport.periodStart)
        assertEquals(2, sport.periodEnd)
        assertEquals("上午", sport.daypart)
        assertEquals("一", sport.slotLabel)
        assertTrue(sport.coordinate.isNotEmpty())
        assertEquals("Sheet1", sport.sheet)
        assertTrue(sport.rawLine.isNotEmpty())
        assertTrue(sport.ok)
    }

    @Test
    fun knownSampleMultiRoomCourseHasThreeRooms() {
        val sample = knownSample()
        assumeTrue("本地样例课表未提供（samples/excel 不入库）", sample != null)
        val result = extractChengduWenliV1(readWorkbook(sample!!.readBytes()))
        val finance = result.occurrences.filter { it.courseCode == "FX3001004" }
        assertTrue(finance.size >= 3)
        val rooms = finance.mapNotNull { it.roomText }.toSet()
        assertTrue(rooms.any { it.contains("C404") })
        assertTrue(rooms.any { it.contains("C304") })
        assertTrue(rooms.any { it.contains("C301") })
        for (occ in finance) {
            assertEquals(3, occ.weekday)
            assertEquals(5, occ.periodStart)
            assertEquals(7, occ.periodEnd)
        }
    }

    @Test
    fun knownSampleHistoryWeekExpansion() {
        val sample = knownSample()
        assumeTrue("本地样例课表未提供（samples/excel 不入库）", sample != null)
        val result = extractChengduWenliV1(readWorkbook(sample!!.readBytes()))
        val history = result.occurrences.filter {
            it.courseCode == "133951" && it.periodStart == 1
        }
        val target = history.filter { it.weekText == "2-4, 8-16双周" }
        assertTrue("expected the 2-4, 8-16双周 history occurrence", target.isNotEmpty())
        val occ = target[0]
        assertEquals(setOf(2, 4, 8, 10, 12, 14, 16), occ.expandedWeeks().toSet())
        assertEquals(2, occ.weekday)
        assertEquals(1, occ.periodStart)
        assertEquals(2, occ.periodEnd)
    }

    @Test
    fun knownSampleGridAndMerged() {
        val sample = knownSample()
        assumeTrue("本地样例课表未提供（samples/excel 不入库）", sample != null)
        val bytes = sample!!.readBytes()
        assertEquals("xls", detectFormat(bytes))
        val workbook = readWorkbook(bytes)
        assertTrue("Sheet1" in workbook.sheets)
        val grid = workbook.sheets["Sheet1"]!!
        assertEquals("成都文理学院2026-2027学年第一学期课表", grid[0][1])
        assertTrue("Sheet1" in workbook.merged)
        assertTrue(workbook.merged["Sheet1"]!!.isNotEmpty())
    }

    @Test
    fun knownSampleIsRealXlsContainer() {
        val sample = knownSample()
        assumeTrue("本地样例课表未提供（samples/excel 不入库）", sample != null)
        assertEquals("xls", detectFormat(sample!!.readBytes()))
        val workbook = readWorkbook(sample.readBytes())
        assertTrue(workbook.sheets.isNotEmpty())
    }
}
