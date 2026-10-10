import { Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { useAuth } from "./auth/AuthContext";
import { ClassesPage } from "./pages/ClassesPage";
import { ImportPreviewPage } from "./pages/ImportPreviewPage";
import { ImportUploadPage } from "./pages/ImportUploadPage";
import { LoginPage } from "./pages/LoginPage";
import { SchedulePage } from "./pages/SchedulePage";

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { token } = useAuth();
  if (!token) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

function Shell({ children }: { children: React.ReactNode }) {
  const { logout } = useAuth();
  const navigate = useNavigate();
  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">Colink 管理端</div>
        <nav>
          <button type="button" className="link" onClick={() => navigate("/classes")}>
            班级
          </button>
          <button type="button" className="link" onClick={() => navigate("/import")}>
            导入课表
          </button>
          <button type="button" className="link" onClick={() => navigate("/schedule")}>
            课表
          </button>
          <button
            type="button"
            className="link"
            onClick={() => {
              logout();
              navigate("/login");
            }}
          >
            退出
          </button>
        </nav>
      </header>
      <main className="content">{children}</main>
    </div>
  );
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/classes"
        element={
          <RequireAuth>
            <Shell>
              <ClassesPage />
            </Shell>
          </RequireAuth>
        }
      />
      <Route
        path="/import"
        element={
          <RequireAuth>
            <Shell>
              <ImportUploadPage />
            </Shell>
          </RequireAuth>
        }
      />
      <Route
        path="/import/:importId"
        element={
          <RequireAuth>
            <Shell>
              <ImportPreviewPage />
            </Shell>
          </RequireAuth>
        }
      />
      <Route
        path="/schedule"
        element={
          <RequireAuth>
            <Shell>
              <SchedulePage />
            </Shell>
          </RequireAuth>
        }
      />
      <Route path="*" element={<Navigate to="/import" replace />} />
    </Routes>
  );
}
