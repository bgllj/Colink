import type {
  AdminUser,
  ConfirmOut,
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
      const data = (await response.json()) as { detail?: string };
      if (data.detail) {
        message = data.detail;
      }
    } catch {
      // keep generic message
    }
    throw new ApiError(response.status, message);
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

export function ingestImport(file: File, maxWeek?: number | null): Promise<ImportIngestResponse> {
  const formData = new FormData();
  formData.append("file", file);
  if (maxWeek != null) {
    formData.append("max_week", String(maxWeek));
  }
  return request<ImportIngestResponse>("/imports", { method: "POST", formData });
}

export function fetchImport(importId: string): Promise<ImportPreviewOut> {
  return request<ImportPreviewOut>(`/imports/${encodeURIComponent(importId)}`);
}

export function confirmImport(importId: string, rowIds: string[]): Promise<ConfirmOut> {
  return request<ConfirmOut>(`/imports/${encodeURIComponent(importId)}/confirm`, {
    method: "POST",
    body: { row_ids: rowIds },
  });
}

export function fetchSchedule(week?: number | null): Promise<ScheduleOut> {
  const query = week != null ? `?week=${week}` : "";
  return request<ScheduleOut>(`/schedule${query}`);
}
