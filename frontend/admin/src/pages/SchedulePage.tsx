import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import {
  ApiError,
  fetchClasses,
  fetchClassSchedule,
  updateClassSemester,
} from "../api/client";
import type { ClassOut, ScheduleOut } from "../api/types";

const WEEKDAYS = ["", "周一", "周二", "周三", "周四", "周五", "周六", "周日"];

export function SchedulePage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [classes, setClasses] = useState<ClassOut[]>([]);
  const [classId, setClassId] = useState(searchParams.get("class_id") ?? "");
  const [schedule, setSchedule] = useState<ScheduleOut | null>(null);
  const [week, setWeek] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [editingStartDate, setEditingStartDate] = useState(false);
  const [startDateDraft, setStartDateDraft] = useState("");
  const [startDateError, setStartDateError] = useState<string | null>(null);
  const [startDateSaving, setStartDateSaving] = useState(false);
  const classIdRef = useRef(classId);
  classIdRef.current = classId;

  useEffect(() => {
    fetchClasses()
      .then((list) => {
        setClasses(list);
        if (!classId && list.length > 0) {
          setClassId(list[0].id);
        }
      })
      .catch(() => setClasses([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!classId) {
      setSchedule(null);
      return;
    }
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
    fetchClassSchedule(classId, parsed)
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
  }, [classId, week]);

  function onSelectClass(next: string) {
    setClassId(next);
    setEditingStartDate(false);
    setStartDateError(null);
    const params = new URLSearchParams(searchParams);
    if (next) {
      params.set("class_id", next);
    } else {
      params.delete("class_id");
    }
    setSearchParams(params);
  }

  function onStartEdit() {
    setStartDateDraft(schedule?.semester.start_date ?? "");
    setStartDateError(null);
    setEditingStartDate(true);
  }

  function onStartCancel() {
    setEditingStartDate(false);
    setStartDateError(null);
    setStartDateDraft("");
  }

  async function onStartSave() {
    const targetClassId = classId;
    if (!targetClassId) return;
    const value = startDateDraft.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
      setStartDateError("请选择有效日期（YYYY-MM-DD）");
      return;
    }
    setStartDateSaving(true);
    setStartDateError(null);
    try {
      const semester = await updateClassSemester(targetClassId, {
        start_date: value,
      });
      if (classIdRef.current !== targetClassId) return;
      setSchedule((prev) =>
        prev ? { ...prev, semester } : prev,
      );
      setEditingStartDate(false);
    } catch (err) {
      if (classIdRef.current !== targetClassId) return;
      setStartDateError(
        err instanceof ApiError ? err.message : "保存开学日期失败",
      );
    } finally {
      if (classIdRef.current === targetClassId) {
        setStartDateSaving(false);
      }
    }
  }

  return (
    <div className="stack">
      <section className="card stack">
        <h1>已入库课表</h1>
        <div className="row">
          <label>
            班级
            <select
              value={classId}
              disabled={startDateSaving}
              onChange={(e) => onSelectClass(e.target.value)}
            >
              <option value="">— 选择班级 —</option>
              {classes.map((cls) => (
                <option key={cls.id} value={cls.id}>
                  {cls.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            按周次过滤（可选）
            <input
              type="number"
              min={1}
              value={week}
              disabled={startDateSaving}
              onChange={(e) => setWeek(e.target.value)}
              placeholder="留空显示全部"
            />
          </label>
          {pending ? <span className="muted">加载中…</span> : null}
        </div>
        {schedule ? (
          <dl className="meta-grid">
            <div>
              <dt>班级</dt>
              <dd>{schedule.class_name}</dd>
            </div>
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
              <dd>
                {editingStartDate ? (
                  <span className="row">
                    <input
                      type="date"
                      value={startDateDraft}
                      onChange={(e) => setStartDateDraft(e.target.value)}
                      aria-label="开学日期"
                    />
                    <button
                      type="button"
                      onClick={onStartSave}
                      disabled={startDateSaving}
                    >
                      {startDateSaving ? "保存中…" : "保存"}
                    </button>
                    <button
                      type="button"
                      onClick={onStartCancel}
                      disabled={startDateSaving}
                    >
                      取消
                    </button>
                  </span>
                ) : (
                  <span className="row">
                    <span>{schedule.semester.start_date ?? "—"}</span>
                    <button type="button" onClick={onStartEdit}>
                      修改
                    </button>
                  </span>
                )}
                {startDateError ? (
                  <div className="error">{startDateError}</div>
                ) : null}
              </dd>
            </div>
            <div>
              <dt>最大周次</dt>
              <dd>{schedule.semester.max_week ?? "—"}</dd>
            </div>
          </dl>
        ) : null}
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
                    <td>
                      {meeting.weeks.length > 0
                        ? meeting.weeks.join(",")
                        : (meeting.week_text ?? "—")}
                    </td>
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
              {!schedule && classId ? (
                <tr>
                  <td colSpan={6} className="muted">
                    请选择班级后查看课表
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
