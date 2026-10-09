import { useEffect, useState } from "react";
import { ApiError, fetchSchedule } from "../api/client";
import type { ScheduleOut } from "../api/types";

const WEEKDAYS = ["", "周一", "周二", "周三", "周四", "周五", "周六", "周日"];

export function SchedulePage() {
  const [schedule, setSchedule] = useState<ScheduleOut | null>(null);
  const [week, setWeek] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setPending(true);
    setError(null);
    const trimmed = week.trim();
    if (trimmed !== "" && (!Number.isInteger(Number(trimmed)) || Number(trimmed) < 1)) {
      setPending(false);
      setError("周次必须是不小于 1 的整数");
      setSchedule(null);
      return;
    }
    const parsed = trimmed === "" ? null : Number(trimmed);
    fetchSchedule(parsed)
      .then((data) => {
        if (!cancelled) setSchedule(data);
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err instanceof ApiError ? err.message : "加载课表失败");
        }
      })
      .finally(() => {
        if (!cancelled) setPending(false);
      });
    return () => {
      cancelled = true;
    };
  }, [week]);

  return (
    <div className="stack">
      <section className="card stack">
        <h1>已入库课表</h1>
        {schedule ? (
          <dl className="meta-grid">
            <div>
              <dt>学年</dt>
              <dd>{schedule.semester.academic_year ?? "—"}</dd>
            </div>
            <div>
              <dt>学期</dt>
              <dd>{schedule.semester.semester_name ?? "—"}</dd>
            </div>
            <div>
              <dt>开学日期</dt>
              <dd>{schedule.semester.start_date ?? "—"}</dd>
            </div>
            <div>
              <dt>最大周次</dt>
              <dd>{schedule.semester.max_week ?? "—"}</dd>
            </div>
          </dl>
        ) : null}
        <div className="row">
          <label>
            按周次过滤（可选）
            <input
              type="number"
              min={1}
              value={week}
              onChange={(e) => setWeek(e.target.value)}
              placeholder="留空显示全部"
            />
          </label>
          {pending ? <span className="muted">加载中…</span> : null}
        </div>
        {error ? <div className="error">{error}</div> : null}
      </section>

      <section className="card">
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>课程代码</th>
                <th>课程名</th>
                <th>星期</th>
                <th>节次</th>
                <th>周次</th>
                <th>地点</th>
              </tr>
            </thead>
            <tbody>
              {schedule?.courses.flatMap((course) =>
                course.meetings.map((meeting) => (
                  <tr key={`${course.course_id}-${meeting.meeting_id}`}>
                    <td className="mono">{course.course_code}</td>
                    <td>{course.name}</td>
                    <td>{WEEKDAYS[meeting.weekday] ?? meeting.weekday}</td>
                    <td>
                      {meeting.period_start ?? "—"}
                      {meeting.period_end != null && meeting.period_end !== meeting.period_start
                        ? `–${meeting.period_end}`
                        : ""}
                    </td>
                    <td>{meeting.weeks.length > 0 ? meeting.weeks.join(",") : (meeting.week_text ?? "—")}</td>
                    <td>{meeting.room_text ?? "—"}</td>
                  </tr>
                )),
              )}
              {schedule && schedule.courses.length === 0 ? (
                <tr>
                  <td colSpan={6} className="muted">
                    暂无已确认课程
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
