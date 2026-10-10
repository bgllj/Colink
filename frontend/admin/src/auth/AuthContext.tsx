import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import {
  getToken,
  login as apiLogin,
  setToken,
  setUnauthorizedListener,
} from "../api/client";

interface AuthContextValue {
  token: string | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState<string | null>(() => getToken());

  const logout = useCallback(() => {
    setToken(null);
    setTokenState(null);
  }, []);

  // A 401 from any request must clear the in-memory session too, not just
  // sessionStorage — otherwise RequireAuth keeps rendering protected pages
  // and LoginPage bounces the user back into the app.
  useEffect(() => {
    setUnauthorizedListener(() => setTokenState(null));
    return () => setUnauthorizedListener(null);
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const result = await apiLogin(username, password);
    setToken(result.access_token);
    setTokenState(result.access_token);
  }, []);

  const value = useMemo(() => ({ token, login, logout }), [token, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error("useAuth must be used within AuthProvider");
  }
  return value;
}
