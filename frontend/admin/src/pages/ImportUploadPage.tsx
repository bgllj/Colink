import { FormEvent, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError, fetchClasses, ingestImport } from "../api/client";
import type { ClassOut, ImportIngestResponse } from "../api/types";
import { IssueList } from "../components/IssueList";

export function ImportUploadPage() {
  const navigate = useNavigate();
  const [file, setFile] = useState<File | null>(null);
  const [maxWeek, setMaxWeek] = useState("");
  const [classes, setClasses] = useState<ClassOut[]>([]);
  const [classId, setClassId] = useState("");
  const [className, setClassName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState<ImportIngestResponse | null>(null);

  useEffect(() => {
    fetchClasses()
      .then(setClasses)
      .catch(() => setClasses([]));
  }, []);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    if (!file) {
      setError("请选择要导入的课表文件");
      return;
    }
    if (!classId && !className.trim()) {
      setError("请选择已有班级，或填写新班级名称");
      return;
    }
    setPending(true);
    setError(null);
    try {
      const parsedMaxWeek = maxWeek.trim() === "" ? null : Number(maxWeek);
      if (parsedMaxWeek != null && (!Number.isInteger(parsedMaxWeek) || parsedMaxWeek < 1)) {
        setError("最大周次必须是不小于 1 的整数");
        return;
      }
      const response = await ingestImport(file, {
        maxWeek: parsedMaxWeek,
        classId: classId || null,
        className: classId ? null : className.trim(),
      });
      setResult(response);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "上传失败，请稍后重试");
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="stack">
      <section className="card stack">
        <h1>导入课表</h1>
        <p className="muted">
          支持 <code>.xls</code> / <code>.xlsx</code>。上传后先解析预览，确认无误再入库到目标班级。
        </p>
        <form className="stack" onSubmit={onSubmit}>
          <label>
            目标班级
            <select value={classId} onChange={(e) => setClassId(e.target.value)}>
              <option value="">— 新建班级 —</option>
              {classes.map((cls) => (
                <option key={cls.id} value={cls.id}>
                  {cls.name}
                </option>
              ))}
            </select>
          </label>
          {!classId ? (
            <label>
              新班级名称
              <input
                value={className}
                onChange={(e) => setClassName(e.target.value)}
                placeholder="如 2025计算机科学与技术9"
              />
            </label>
          ) : null}
          <label>
            课表文件
            <input
              type="file"
              accept=".xls,.xlsx"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
          </label>
          <label>
            最大周次（可选）
            <input
              type="number"
              min={1}
              value={maxWeek}
              onChange={(e) => setMaxWeek(e.target.value)}
              placeholder="留空则使用解析结果"
            />
          </label>
          {error ? <div className="error">{error}</div> : null}
          <div className="row">
            <button className="primary" type="submit" disabled={pending || !file}>
              {pending ? "上传解析中…" : "上传并解析"}
            </button>
          </div>
        </form>
      </section>

      {result ? (
        <section className="card stack">
          <h2>上传结果</h2>
          <dl className="meta-grid">
            <div>
              <dt>导入批次</dt>
              <dd className="mono">{result.import_id}</dd>
            </div>
            <div>
              <dt>状态</dt>
              <dd>{result.status}</dd>
            </div>
            <div>
              <dt>最大周次</dt>
              <dd>{result.max_week ?? "—"}</dd>
            </div>
            <div>
              <dt>重复文件</dt>
              <dd className="mono">{result.duplicate_of ?? "—"}</dd>
            </div>
          </dl>
          {result.issues.length > 0 ? (
            <div>
              <h3>批次问题</h3>
              <IssueList issues={result.issues} />
            </div>
          ) : null}
          <div className="row">
            <button
              className="primary"
              type="button"
              onClick={() => navigate(`/import/${result.import_id}`)}
            >
              进入逐行审核
            </button>
          </div>
        </section>
      ) : null}
    </div>
  );
}
