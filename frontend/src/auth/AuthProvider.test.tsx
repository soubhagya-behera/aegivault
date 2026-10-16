import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { AuthProvider, useAuth } from "./AuthProvider";

/**
 * Provider integration (no mocks of the auth module itself): the real
 * login()/register() sequence runs against a stubbed fetch, proving the
 * frontend performs login/register THEN profile lookup with the returned
 * bearer token, and only authenticates when both succeed.
 */

const noop = () => {};

function Probe() {
  const { status, user, login, register, logout } = useAuth();
  return (
    <div>
      <p data-testid="status">{status}</p>
      <p data-testid="user">{user ? `${user.email}|${user.displayName ?? ""}` : "none"}</p>
      <button type="button" onClick={() => login({ email: "ada@example.com", password: "s3cret" }).catch(noop)}>
        do-login
      </button>
      <button
        type="button"
        onClick={() => register({ email: "new@example.com", password: "s3cret-pass" }).catch(noop)}
      >
        do-register
      </button>
      <button type="button" onClick={() => logout()}>
        do-logout
      </button>
    </div>
  );
}

function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

const TOKEN_BODY = {
  token: "token-abc",
  tokenType: "Bearer",
  expiresIn: 3600,
  userId: "u-1",
  email: "ada@example.com",
  roles: ["USER"],
};

const PROFILE_BODY = {
  userId: "u-1",
  email: "ada@example.com",
  displayName: null,
  roles: ["USER"],
};

function stubFetch(impl: (url: string, init?: RequestInit) => Promise<Response> | Response) {
  const spy = vi.fn(impl);
  vi.stubGlobal("fetch", spy);
  return spy;
}

function renderProbe() {
  return render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("AuthProvider session sequence", () => {
  it("logs in with login-then-me and authenticates on both succeeding", async () => {
    const fetchSpy = stubFetch((url) =>
      url === "/api/auth/me" ? jsonResponse(PROFILE_BODY, 200) : jsonResponse(TOKEN_BODY, 200),
    );
    renderProbe();

    fireEvent.click(screen.getByRole("button", { name: "do-login" }));

    await waitFor(() => expect(screen.getByTestId("status").textContent).toBe("authenticated"));
    expect(screen.getByTestId("user").textContent).toBe("ada@example.com|");

    expect(fetchSpy).toHaveBeenCalledTimes(2);
    const [loginUrl, loginInit] = fetchSpy.mock.calls[0] as [string, RequestInit];
    expect(loginUrl).toBe("/api/auth/login");
    expect(loginInit.method).toBe("POST");
    expect(JSON.parse(loginInit.body as string)).toEqual({
      email: "ada@example.com",
      password: "s3cret",
    });
    const [meUrl, meInit] = fetchSpy.mock.calls[1] as [string, RequestInit];
    expect(meUrl).toBe("/api/auth/me");
    expect((meInit.headers as Record<string, string>).Authorization).toBe("Bearer token-abc");
  });

  it("registers with register-then-me and omits a blank display name", async () => {
    const fetchSpy = stubFetch((url) =>
      url === "/api/auth/me"
        ? jsonResponse({ ...PROFILE_BODY, email: "new@example.com" }, 200)
        : jsonResponse({ ...TOKEN_BODY, email: "new@example.com" }, 201),
    );
    renderProbe();

    fireEvent.click(screen.getByRole("button", { name: "do-register" }));

    await waitFor(() => expect(screen.getByTestId("status").textContent).toBe("authenticated"));
    const [registerUrl, registerInit] = fetchSpy.mock.calls[0] as [string, RequestInit];
    expect(registerUrl).toBe("/api/auth/register");
    expect(JSON.parse(registerInit.body as string)).toEqual({
      email: "new@example.com",
      password: "s3cret-pass",
    });
  });

  it("stays signed out when credentials are rejected", async () => {
    stubFetch(() => jsonResponse({ message: "Invalid email or password." }, 401));
    renderProbe();

    fireEvent.click(screen.getByRole("button", { name: "do-login" }));

    await waitFor(() => expect(screen.getByTestId("user").textContent).toBe("none"));
    expect(screen.getByTestId("status").textContent).toBe("unauthenticated");
  });

  it("does not authenticate when the profile lookup fails after login", async () => {
    stubFetch((url) =>
      url === "/api/auth/me"
        ? jsonResponse({ message: "Invalid email or password." }, 401)
        : jsonResponse(TOKEN_BODY, 200),
    );
    renderProbe();

    fireEvent.click(screen.getByRole("button", { name: "do-login" }));

    await waitFor(() => expect(screen.getByTestId("user").textContent).toBe("none"));
    expect(screen.getByTestId("status").textContent).toBe("unauthenticated");
  });

  it("stays signed out when the backend is unreachable", async () => {
    stubFetch(() => Promise.reject(new TypeError("Failed to fetch")));
    renderProbe();

    fireEvent.click(screen.getByRole("button", { name: "do-login" }));

    await waitFor(() => expect(screen.getByTestId("user").textContent).toBe("none"));
    expect(screen.getByTestId("status").textContent).toBe("unauthenticated");
  });

  it("clears the session on logout", async () => {
    stubFetch((url) =>
      url === "/api/auth/me" ? jsonResponse(PROFILE_BODY, 200) : jsonResponse(TOKEN_BODY, 200),
    );
    renderProbe();

    fireEvent.click(screen.getByRole("button", { name: "do-login" }));
    await waitFor(() => expect(screen.getByTestId("status").textContent).toBe("authenticated"));

    fireEvent.click(screen.getByRole("button", { name: "do-logout" }));
    expect(screen.getByTestId("status").textContent).toBe("unauthenticated");
    expect(screen.getByTestId("user").textContent).toBe("none");
  });
});
