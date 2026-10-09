import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, request } from "./client";

// Control the API base URL deterministically (the shared client reads it here).
vi.mock("../env", () => ({ API_BASE_URL: "/api" }));

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/** Reads the RequestInit a mocked fetch was called with (via a Headers view). */
function initOf(mock: ReturnType<typeof vi.fn>, call = 0): RequestInit {
  const args = mock.mock.calls[call];
  return (args?.[1] ?? {}) as RequestInit;
}

function urlOf(mock: ReturnType<typeof vi.fn>, call = 0): string {
  const args = mock.mock.calls[call];
  return String(args?.[0]);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("api client — request()", () => {
  it("builds the URL from the base without duplicating /api", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);

    await request("/auth/login", { method: "POST", body: { a: 1 } });

    expect(urlOf(fetchMock)).toBe("/api/auth/login");
  });

  it("normalizes a missing leading slash and never produces a double slash", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);

    await request("auth/me", { token: "t" });

    expect(urlOf(fetchMock)).toBe("/api/auth/me");
  });

  it("sets Authorization: Bearer only when a token is provided", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);

    await request("/auth/me", { token: "secret-token" });
    expect(new Headers(initOf(fetchMock).headers).get("Authorization")).toBe(
      "Bearer secret-token",
    );

    fetchMock.mockClear();
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));
    await request("/auth/login", { method: "POST", body: {} });
    expect(new Headers(initOf(fetchMock).headers).has("Authorization")).toBe(false);
  });

  it("sets JSON content-type and serializes the body only for JSON requests", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);

    await request("/auth/login", { method: "POST", body: { email: "a@b.co" } });
    const withBody = initOf(fetchMock);
    expect(new Headers(withBody.headers).get("Content-Type")).toBe("application/json");
    expect(withBody.body).toBe(JSON.stringify({ email: "a@b.co" }));

    fetchMock.mockClear();
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));
    await request("/auth/me", { token: "t" });
    expect(new Headers(initOf(fetchMock).headers).has("Content-Type")).toBe(false);
    expect(initOf(fetchMock).body).toBeUndefined();
  });

  it("returns undefined for HTTP 204 without parsing a body", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(request("/thing")).resolves.toBeUndefined();
  });

  it("normalizes non-2xx into ApiError and preserves the status", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response("{}", { status: 409 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      request("/auth/register", { method: "POST", body: {} }),
    ).rejects.toMatchObject({ status: 409 });
  });

  it("surfaces a safe server message when the backend provides one", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ message: "Email is already registered." }, 409));
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      request("/auth/register", { method: "POST", body: {} }),
    ).rejects.toThrowError("Email is already registered.");
  });

  it("uses a controlled message and never leaks unsafe body content", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ detail: "should-not-leak", errors: [{ msg: "x" }] }, 400),
    );
    vi.stubGlobal("fetch", fetchMock);

    const error = await request("/x").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(400);
    expect((error as ApiError).message).not.toContain("should-not-leak");
  });

  it("handles empty and invalid JSON success bodies without leaking details", async () => {
    const emptyMock = vi.fn().mockResolvedValue(new Response("", { status: 200 }));
    vi.stubGlobal("fetch", emptyMock);
    await expect(request("/x")).rejects.toBeInstanceOf(ApiError);

    vi.unstubAllGlobals();
    const invalidMock = vi.fn().mockResolvedValue(new Response("not-json", { status: 200 }));
    vi.stubGlobal("fetch", invalidMock);
    await expect(request("/x")).rejects.toBeInstanceOf(ApiError);
  });

  it("forwards the AbortSignal to fetch", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);
    const controller = new AbortController();

    await request("/x", { signal: controller.signal });

    expect(initOf(fetchMock).signal).toBe(controller.signal);
  });

  it("re-throws AbortError and wraps other transport failures", async () => {
    const abortError = new DOMException("aborted", "AbortError");
    const abortMock = vi.fn().mockRejectedValue(abortError);
    vi.stubGlobal("fetch", abortMock);
    await expect(request("/x")).rejects.toBe(abortError);

    vi.unstubAllGlobals();
    const netMock = vi.fn().mockRejectedValue(new TypeError("network down"));
    vi.stubGlobal("fetch", netMock);
    const error = await request("/x").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(0);
  });
});
