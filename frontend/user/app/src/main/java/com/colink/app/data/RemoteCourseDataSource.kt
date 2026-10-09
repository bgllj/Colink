package com.colink.app.data

import com.colink.app.Course
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

object BackendConfig {
    /** Emulator reaches the host machine loopback through this address. */
    const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"
}

/** Raised for failures whose message is already a user-facing Chinese sentence. */
class BackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

class RemoteCourseDataSource(
    private val baseUrlProvider: () -> String,
    private val client: OkHttpClient = defaultClient(),
) : CourseDataSource {

    override suspend fun fetchCourses(): Result<SchedulePayload> = withContext(Dispatchers.IO) {
        try {
            Result.success(loadSchedule())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private fun loadSchedule(): SchedulePayload {
        val baseUrl = normalizeBaseUrl(baseUrlProvider())
        if (baseUrl.isEmpty()) {
            throw BackendException("未配置服务器地址，请在“我的”页面设置")
        }
        val request = Request.Builder().url("$baseUrl/schedule").get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw BackendException("后端返回错误（HTTP ${response.code}）")
                }
                val body = response.body?.string()
                    ?: throw BackendException("后端返回空响应")
                parseSchedule(body)
            }
        } catch (illegal: IllegalArgumentException) {
            throw BackendException("服务器地址格式不正确，请填写 http://IP:端口", illegal)
        }
    }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.contains("://")) trimmed else "http://$trimmed"
    }

    private fun parseSchedule(body: String): SchedulePayload = try {
        val root = JSONObject(body)
        val courses = mutableListOf<Course>()
        val courseArray = root.optJSONArray("courses") ?: org.json.JSONArray()
        for (i in 0 until courseArray.length()) {
            val courseObject = courseArray.getJSONObject(i)
            val code = courseObject.optString("course_code")
            val name = courseObject.optString("name")
            val meetings = courseObject.optJSONArray("meetings") ?: continue
            for (j in 0 until meetings.length()) {
                val meeting = meetings.getJSONObject(j)
                courses += Course(
                    id = "meeting-${meeting.optLong("meeting_id")}",
                    name = name,
                    code = code,
                    day = meeting.optInt("weekday"),
                    start = meeting.optIntOrNull("period_start"),
                    end = meeting.optIntOrNull("period_end"),
                    weeks = meeting.optIntList("weeks").toSet(),
                    room = meeting.optString("room_text").ifEmpty { "待补充" },
                )
            }
        }
        val semesterObject = root.optJSONObject("semester")
        SchedulePayload(
            courses = courses,
            semester = SemesterMeta(
                startDate = semesterObject?.optString("start_date")?.takeIf { it.isNotEmpty() && it != "null" },
                maxWeek = semesterObject?.optIntOrNull("max_week"),
            ),
        )
    } catch (malformed: JSONException) {
        throw BackendException("课表数据格式异常", malformed)
    }

    companion object {
        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
    }
}

/** Maps a load failure to the Chinese sentence shown in the schedule UI. */
fun Throwable.toUserMessage(): String = when (this) {
    is BackendException -> message ?: "加载课表失败"
    is SocketTimeoutException -> "连接服务器超时，请稍后重试"
    is JSONException -> "课表数据格式异常"
    is IOException -> "无法连接后端服务，请检查服务器地址与网络"
    else -> message?.takeIf { it.isNotBlank() } ?: "加载课表失败"
}

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key) || !has(key)) null else optInt(key)

private fun JSONObject.optIntList(key: String): List<Int> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).map { array.getInt(it) }
}
