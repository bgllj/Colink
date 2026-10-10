import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ApiError, confirmImport, fetchImport } from "../api/client";
import type { ImportPreviewOut, ImportRowOut } from "../api/types";
import { IssueList } from "../components/IssueList";
import { allVisibleSelected, toggleAllVisible, toggleRow } from "./selection";

function formatWeekRanges(row: ImportRowOut): string {
  if (row.week_ranges.length === 0) return "—";
  return row.week_ranges
    .map((range) => {
      const start = range.start as number | undefined;
      const end = range.end as number | undefined;
      const parity = (range.parity as string | undefined) ?? "ALL";
      const base = start === end ? `${start}` : `${start}-${end}`;
      return parity === "ALL" ? base : `${base}(${parity})`;
    })
    .join(", ");
}

function formatSource(row: ImportRowOut): string {
  const parts = [row.sheet, row.coordinate].filter(Boolean).join(" ");
  if (!parts && row.line_index == null) return "—";
  return row.line_index != null ? `${parts} #${row.line_index}` : parts;
}

export function ImportPreviewPage() {
  const { importId = "" } = useParams();
  const navigate = useNavigate();
  const [preview, setPreview] = useState<ImportPreviewOut | null>(null);
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [confirmPending, setConfirmPending] = useState(false);
  const [showIssuesOnly, setShowIssuesOnly] = useState(false);
  const [replace, setReplace] = useState(false);

  const load = useCallback(async () => {
    setPending(true);
    setError(null);
    try {
      const data = await fetchImport(importId);
      setPreview(data);
      setSelected(new Set(data.rows.filter((row) => row.selected).map((row) => row.row_id)));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载导入批次失败");
    } finally {
      setPending(false);
    }
  }, [importId]);

  useEffect(() => {
    void load();
  }, [load]);

  const rows = useMemo(() => {
    if (!preview) return [];
    return showIssuesOnly ? preview.rows.filter((row) => row.issues.length > 0) : preview.rows;
  }, [preview, showIssuesOnly]);

  const visibleIds = useMemo(() => rows.map((row) => row.row_id), [rows]);
  const everyVisibleSelected = allVisibleSelected(selected, visibleIds);

  function onToggleRow(rowId: string) {
    setSelected((prev) => toggleRow(prev, rowId));
  }

  function onToggleAllVisible() {
    setSelected((prev) => toggleAllVisible(prev, visibleIds, everyVisibleSelected));
  }

  async function onConfirm() {
    if (!preview) return;
    setConfirmPending(true);
    setError(null);
    setNotice(null);
    try {
      const meta = preview.meta as { class_id?: string | null; class_name?: string | null };
      const result = await confirmImport(preview.import_id, {
        row_ids: [...selected],
        class_id: meta.class_id ?? null,
        class_name: meta.class_id ? null : (meta.class_name ?? null),
        replace,
      });
      setNotice(`确认完成：成功入库 ${result.rows_confirmed} 行，状态 ${result.status}`);
      if (result.issues.length > 0) {
        setPreview((prev) =>
          prev ? { ...prev, issues: [...prev.issues, ...result.issues] } : prev,
        );
      }
      navigate(result.class_id ? `/schedule?class_id=${result.class_id}` : "/schedule");
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError(
          `${err.message}。若确认要覆盖该班现有课表，请勾选「替换已有课表」后再次确认。`,
        );
      } else {
        setError(err instanceof ApiError ? err.message : "确认入库失败");
      }
    } finally {
      setConfirmPending(false);
    }
  }

  if (pending && !preview) {
    return <p className="muted">加载中…</p>;
  }

  if (!preview) {
    return error ? <div className="error">{error}</div> : null;
  }

  return (
    <div className="stack">
      <section className="card stack">
        <h1>导入审核</h1>
        <dl className="meta-grid">
          <div>
            <dt>导入批次</dt>
            <dd className="mono">{preview.import_id}</dd>
          </div>
          <div>
            <dt>状态</dt>
            <dd>{preview.status}</dd>
          </div>
          <div>
            <dt>文件名</dt>
            <dd>{String(preview.meta.original_filename ?? "—")}</dd>
          </div>
          <div>
            <dt>解析 profile</dt>
            <dd>{String(preview.meta.profile ?? "—")}</dd>
          </div>
          <div>
            <dt>学年 / 学期</dt>
            <dd>
              {String(preview.meta.academic_year ?? "—")} / {String(preview.meta.semester_name ?? "—")}
            </dd>
          </div>
          <div>
            <dt>开学日期</dt>
            <dd>{String(preview.meta.start_date ?? "—")}</dd>
          </div>
          <div>
            <dt>班级</dt>
            <dd>{String(preview.meta.class_name ?? "—")}</dd>
          </div>
          <div>
            <dt>最大周次</dt>
            <dd>{preview.max_week ?? "—"}</dd>
          </div>
          <div>
            <dt>文件哈希</dt>
            <dd className="mono">{String(preview.meta.file_hash ?? "—")}</dd>
          </div>
        </dl>
        {preview.issues.length > 0 ? (
          <div>
            <h3>批次问题</h3>
            <IssueList issues={preview.issues} />
          </div>
        ) : null}
      </section>

      <section className="card stack">
        <div className="row">
          <h2 style={{ margin: 0, flex: 1 }}>逐行审核（{preview.rows.length} 行）</h2>
          <label className="row" style={{ gap: "0.4rem" }}>
            <input
              type="checkbox"
              checked={showIssuesOnly}
              onChange={(e) => setShowIssuesOnly(e.target.checked)}
            />
            仅看有问题的行
          </label>
        </div>

        {error ? <div className="error">{error}</div> : null}
        {notice ? (
          <div className="stack">
            <div className="success">{notice}</div>
            <div className="row">
              <button className="secondary" type="button" onClick={() => navigate("/schedule")}>
                查看已入库课表
              </button>
              <button className="secondary" type="button" onClick={() => void load()}>
                重新加载
              </button>
            </div>
          </div>
        ) : null}

        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>
                  <input
                    type="checkbox"
                    checked={everyVisibleSelected}
                    onChange={onToggleAllVisible}
                  />
                </th>
                <th>课程代码</th>
                <th>课程名</th>
                <th>星期</th>
                <th>节次</th>
                <th>周次原文</th>
                <th>解析周次</th>
                <th>地点</th>
                <th>溯源</th>
                <th>状态 / 问题</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.row_id}>
                  <td>
                    <input
                      type="checkbox"
                      checked={selected.has(row.row_id)}
                      onChange={() => onToggleRow(row.row_id)}
                    />
                  </td>
                  <td className="mono">{row.course_code ?? "—"}</td>
                  <td>{row.course_name ?? "—"}</td>
                  <td>{row.weekday ?? "—"}</td>
                  <td>
                    {row.period_start ?? "—"}
                    {row.period_end != null && row.period_end !== row.period_start
                      ? `–${row.period_end}`
                      : ""}
                  </td>
                  <td>{row.week_text ?? "—"}</td>
                  <td>{formatWeekRanges(row)}</td>
                  <td>{row.room_text ?? "—"}</td>
                  <td className="mono">
                    {formatSource(row)}
                    {row.raw_line ? (
                      <div className="muted mono" style={{ whiteSpace: "pre-wrap" }}>
                        {row.raw_line}
                      </div>
                    ) : null}
                  </td>
                  <td>
                    <div>{row.status}</div>
                    {row.issues.length > 0 ? <IssueList issues={row.issues} /> : null}
                  </td>
                </tr>
              ))}
              {rows.length === 0 ? (
                <tr>
                  <td colSpan={10} className="muted">
                    没有可展示的行
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>

        <div className="row">
          <span className="muted">已选 {selected.size} 行</span>
          <label className="row" style={{ gap: "0.4rem" }}>
            <input
              type="checkbox"
              checked={replace}
              onChange={(e) => setReplace(e.target.checked)}
            />
            替换已有课表
          </label>
          <button
            className="primary"
            type="button"
            onClick={() => void onConfirm()}
            disabled={confirmPending || selected.size === 0 || preview.status === "FAILED"}
          >
            {confirmPending ? "确认中…" : "确认入库"}
          </button>
          <button className="secondary" type="button" onClick={() => navigate("/import")}>
            返回上传
          </button>
        </div>
      </section>
    </div>
  );
}
