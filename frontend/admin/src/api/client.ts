import type {
  AdminUser,
  ClassOut,
  ConfirmOut,
  ConfirmRequest,
  ImportIngestResponse,
  ImportPreviewOut,
  LoginResponse,
  ScheduleOut,
} from "./types";

const TOKEN_KEY = "colink_admin_token";

type UnauthorizedListener = () => void;
let unauthorizedListener: UnauthorizedListener | null = null;

/**
 * Register a handler invoked when any authenticated request returns 401.
 * AuthContext uses this to clear in-memory state and bounce to the login page.
 */
export function setUnauthorizedListener(listener: UnauthorizedListener | null): void {
  unauthorizedListener = listener;
}

export function getToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string | null): void {
  if (token === null) {
    sessionStorage.removeItem(TOKEN_KEY);
  } else {
    sessionStorage.setItem(TOKEN_KEY, token);
  }
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

interface RequestOptions {
  method?: string;
  body?: unknown;
  formData?: FormData;
  auth?: boolean;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {};
  const token = options.auth === false ? null : getToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  let body: BodyInit | undefined;
  if (options.formData) {
    body = options.formData;
  } else if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(options.body);
  }

  const response = await fetch(`/api${path}`, {
    method: options.method ?? "GET",
    headers,
    body,
  });

  if (response.status === 401 && options.auth !== false) {
    setToken(null);
    unauthorizedListener?.();
  }

  if (!response.ok) {
    let message = `请求失败（HTTP ${response.status}）`;
    try {
      const data = (await response.json()) as { detail?: unknown };
      if (typeof data.detail === "string") {
        message = data.detail;
      } else if (data.detail && typeof data.detail === "object") {
        const detail = data.detail as { message?: string; hint?: string };
        message = detail.message ?? detail.hint ?? message;
      }
    } catch {
      // keep generic message
    }
    throw new ApiError(response.status, message);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export async function login(username: string, password: string): Promise<LoginResponse> {
  return request<LoginResponse>("/auth/login", {
    method: "POST",
    body: { username, password },
    auth: false,
  });
}

export function fetchMe(): Promise<AdminUser> {
  return request<AdminUser>("/auth/me");
}

export function ingestImport(
  file: File,
  options?: { maxWeek?: number | null; classId?: string | null; className?: string | null },
): Promise<ImportIngestResponse> {
  const formData = new FormData();
  formData.append("file", file);
  if (options?.maxWeek != null) {
    formData.append("max_week", String(options.maxWeek));
  }
  if (options?.classId) {
    formData.append("class_id", options.classId);
  }
  if (options?.className) {
    formData.append("class_name", options.className);
  }
  return request<ImportIngestResponse>("/imports", { method: "POST", formData });
}

export function fetchImport(importId: string): Promise<ImportPreviewOut> {
  return request<ImportPreviewOut>(`/imports/${encodeURIComponent(importId)}`);
}

export function confirmImport(
  importId: string,
  body: ConfirmRequest,
): Promise<ConfirmOut> {
  return request<ConfirmOut>(`/imports/${encodeURIComponent(importId)}/confirm`, {
    method: "POST",
    body,
  });
}

export function fetchClasses(): Promise<ClassOut[]> {
  return request<ClassOut[]>("/classes");
}

export function createClass(body: {
  name: string;
  grade?: string | null;
  major?: string | null;
  department?: string | null;
}): Promise<ClassOut> {
  return request<ClassOut>("/classes", { method: "POST", body });
}

export function updateClass(
  classId: string,
  body: { name?: string; grade?: string | null; major?: string | null; department?: string | null },
): Promise<ClassOut> {
  return request<ClassOut>(`/classes/${encodeURIComponent(classId)}`, {
    method: "PATCH",
    body,
  });
}

export function deleteClass(classId: string): Promise<void> {
  return request<void>(`/classes/${encodeURIComponent(classId)}`, { method: "DELETE" });
}

export function fetchClassSchedule(
  classId: string,
  week?: number | null,
): Promise<ScheduleOut> {
  const query = week != null ? `?week=${week}` : "";
  return request<ScheduleOut>(
    `/classes/${encodeURIComponent(classId)}/schedule${query}`,
  );
}
