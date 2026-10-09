import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  confirmImport,
  fetchSchedule,
  getToken,
  ingestImport,
  login,
  setToken,
  setUnauthorizedListener,
} from "../api/client";

function mockFetchOnce(status: number, body: unknown) {
  const fn = vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  });
  vi.stubGlobal("fetch", fn);
  return fn;
}

beforeEach(() => {
  sessionStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("api client token storage", () => {
  it("stores and clears token", () => {
    setToken("abc");
    expect(getToken()).toBe("abc");
    setToken(null);
    expect(getToken()).toBeNull();
  });
});

describe("api client requests", () => {
  it("sends bearer token when present", async () => {
    setToken("tok-1");
    const fetchMock = mockFetchOnce(200, { courses: [], semester: {} });
    await fetchSchedule();
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer tok-1");
  });

  it("clears token and notifies listener on 401", async () => {
    setToken("tok-2");
    const onUnauthorized = vi.fn();
    setUnauthorizedListener(onUnauthorized);
    try {
      mockFetchOnce(401, { detail: "未登录" });
      await expect(fetchSchedule()).rejects.toBeInstanceOf(ApiError);
      expect(getToken()).toBeNull();
      expect(onUnauthorized).toHaveBeenCalledTimes(1);
    } finally {
      setUnauthorizedListener(null);
    }
  });

  it("login does not send auth header and returns token", async () => {
    const fetchMock = mockFetchOnce(200, {
      access_token: "t",
      token_type: "bearer",
    });
    const result = await login("admin", "pw");
    expect(result.access_token).toBe("t");
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined();
  });

  it("posts multipart for ingest", async () => {
    setToken("tok-3");
    const fetchMock = mockFetchOnce(200, {
      import_id: "i1",
      status: "PARSED",
      issues: [],
    });
    const file = new File(["x"], "table.xlsx");
    await ingestImport(file, 16);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/imports");
    expect(init.method).toBe("POST");
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer tok-3");
  });

  it("confirm posts row_ids", async () => {
    setToken("tok-4");
    const fetchMock = mockFetchOnce(200, {
      import_id: "i1",
      status: "CONFIRMED",
      rows_confirmed: 2,
      issues: [],
    });
    await confirmImport("i1", ["r1", "r2"]);
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({ row_ids: ["r1", "r2"] });
  });
});
