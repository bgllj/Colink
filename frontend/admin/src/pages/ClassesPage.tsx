import { FormEvent, useCallback, useEffect, useState } from "react";
import {
  ApiError,
  createClass,
  deleteClass,
  fetchClasses,
  updateClass,
} from "../api/client";
import type { ClassOut } from "../api/types";

export function ClassesPage() {
  const [classes, setClasses] = useState<ClassOut[]>([]);
  const [name, setName] = useState("");
  const [grade, setGrade] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editName, setEditName] = useState("");

  const load = useCallback(async () => {
    setPending(true);
    setError(null);
    try {
      setClasses(await fetchClasses());
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载班级列表失败");
    } finally {
      setPending(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function onCreate(event: FormEvent) {
    event.preventDefault();
    const trimmed = name.trim();
    if (!trimmed) {
      setError("请填写班级名称");
      return;
    }
    setPending(true);
    setError(null);
    setNotice(null);
    try {
      await createClass({ name: trimmed, grade: grade.trim() || null });
      setName("");
      setGrade("");
      setNotice(`已创建班级「${trimmed}」`);
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "创建班级失败");
    } finally {
      setPending(false);
    }
  }

  async function onStartEdit(cls: ClassOut) {
    setEditingId(cls.id);
    setEditName(cls.name);
  }

  async function onSaveEdit(classId: string) {
    const trimmed = editName.trim();
    if (!trimmed) {
      setError("班级名称不能为空");
      return;
    }
    setPending(true);
    setError(null);
    try {
      await updateClass(classId, { name: trimmed });
      setEditingId(null);
      setNotice("班级已改名");
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "改名失败");
    } finally {
      setPending(false);
    }
  }

  async function onDelete(cls: ClassOut) {
    const ok = window.confirm(
      `确认删除班级「${cls.name}」？\n将级联删除该班全部课程与课表数据，且不可恢复。`,
    );
    if (!ok) return;
    setPending(true);
    setError(null);
    setNotice(null);
    try {
      await deleteClass(cls.id);
      setNotice(`已删除班级「${cls.name}」`);
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "删除班级失败");
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="stack">
      <section className="card stack">
        <h1>班级管理</h1>
        <p className="muted">每个班级恰好一张课程表。删除班级会一并删除其课表。</p>
        <form className="row" onSubmit={onCreate}>
          <label>
            班级名称
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="如 2025计算机科学与技术9"
            />
          </label>
          <label>
            年级（可选）
            <input value={grade} onChange={(e) => setGrade(e.target.value)} placeholder="2025" />
          </label>
          <button className="primary" type="submit" disabled={pending}>
            新建班级
          </button>
        </form>
        {error ? <div className="error">{error}</div> : null}
        {notice ? <div className="success">{notice}</div> : null}
      </section>

      <section className="card">
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>班级名称</th>
                <th>年级</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {classes.map((cls) => (
                <tr key={cls.id}>
                  <td>
                    {editingId === cls.id ? (
                      <input
                        value={editName}
                        onChange={(e) => setEditName(e.target.value)}
                      />
                    ) : (
                      cls.name
                    )}
                  </td>
                  <td>{cls.grade ?? "—"}</td>
                  <td className="row">
                    {editingId === cls.id ? (
                      <>
                        <button
                          className="primary"
                          type="button"
                          disabled={pending}
                          onClick={() => void onSaveEdit(cls.id)}
                        >
                          保存
                        </button>
                        <button
                          className="secondary"
                          type="button"
                          onClick={() => setEditingId(null)}
                        >
                          取消
                        </button>
                      </>
                    ) : (
                      <>
                        <button
                          className="secondary"
                          type="button"
                          onClick={() => void onStartEdit(cls)}
                        >
                          改名
                        </button>
                        <button
                          className="secondary"
                          type="button"
                          disabled={pending}
                          onClick={() => void onDelete(cls)}
                        >
                          删除
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
              {classes.length === 0 ? (
                <tr>
                  <td colSpan={3} className="muted">
                    暂无班级，请先新建
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
