import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { ApiError } from "../../lib/api/client";
import LoginPage from "./LoginPage";

// Mock only the auth hook; the real Field/Button/FormError components and
// the real submit logic in LoginPage stay under test.
const auth = vi.hoisted(() => ({ login: vi.fn() }));
vi.mock("../../auth/AuthProvider", () => ({
  useAuth: () => auth,
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/login"]}>
      <LoginPage />
    </MemoryRouter>,
  );
}

describe("login page (immersive scene)", () => {
  it("renders the welcome heading, subtitle, and real form controls", () => {
    renderPage();

    expect(
      screen.getByRole("heading", { level: 1, name: "Welcome back" }),
    ).toBeTruthy();
    expect(screen.getByText("Sign in to your secure workspace.")).toBeTruthy();
    expect(screen.getByLabelText("Email")).toBeTruthy();
    expect(screen.getByLabelText("Password")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Sign in" })).toBeTruthy();
  });

  it("links the create-account path to /register", () => {
    renderPage();

    expect(
      screen.getByRole("link", { name: "Create account" }).getAttribute("href"),
    ).toBe("/register");
  });

  it("shows the support notice with the correct mailto link", () => {
    renderPage();

    const mail = screen.getByRole("link", {
      name: "soubhagyabehera074@gmail.com",
    });
    expect(mail.getAttribute("href")).toBe(
      "mailto:soubhagyabehera074@gmail.com",
    );
  });

  it("renders four decorative holo panels without replacing the form", () => {
    renderPage();

    expect(document.querySelectorAll('[data-testid="login-holo"]')).toHaveLength(4);
    for (const title of [
      "Your data. Your control.",
      "Protected Data",
      "Policy Governance",
      "Audit & Evidence",
    ]) {
      expect(screen.getByText(title)).toBeTruthy();
    }
    // The real form is still present and labelled.
    expect(screen.getByLabelText("Email")).toBeTruthy();
    expect(screen.getByLabelText("Password")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Sign in" })).toBeTruthy();
  });

  it("rejects an empty submit without calling the API", () => {
    auth.login.mockReset();
    renderPage();

    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(screen.getByRole("alert").textContent).toBe(
      "Enter your email and password.",
    );
    expect(auth.login).not.toHaveBeenCalled();
  });

  it("submits the trimmed email payload", async () => {
    auth.login.mockReset();
    auth.login.mockResolvedValue({ userId: "u-1" });
    renderPage();

    fireEvent.change(screen.getByLabelText("Email"), {
      target: { value: "  ada@example.com  " },
    });
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "s3cret" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(auth.login).toHaveBeenCalledTimes(1);
    expect(auth.login.mock.calls[0]?.[0]).toEqual({
      email: "ada@example.com",
      password: "s3cret",
    });
  });

  it("surfaces API errors without leaking details", async () => {
    auth.login.mockReset();
    auth.login.mockRejectedValue(new ApiError(401, "Invalid email or password."));
    renderPage();

    fireEvent.change(screen.getByLabelText("Email"), {
      target: { value: "ada@example.com" },
    });
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "wrong" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.getByRole("alert").textContent).toBe(
      "Invalid email or password.",
    );
  });

  it("disables the submit button and marks it busy while pending", async () => {
    auth.login.mockReset();
    let resolveLogin: (value: unknown) => void = () => {};
    auth.login.mockImplementation(
      () => new Promise((resolve) => void (resolveLogin = resolve)),
    );
    renderPage();

    fireEvent.change(screen.getByLabelText("Email"), {
      target: { value: "ada@example.com" },
    });
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "s3cret" },
    });
    const submit = screen.getByRole("button", { name: "Sign in" });
    fireEvent.click(submit);

    expect(submit.hasAttribute("disabled")).toBe(true);
    expect(submit.getAttribute("aria-busy")).toBe("true");

    resolveLogin({ userId: "u-1" });
    expect(await screen.findByRole("button", { name: "Sign in" })).toBeTruthy();
    expect(
      screen.getByRole("button", { name: "Sign in" }).hasAttribute("disabled"),
    ).toBe(false);
  });
});
