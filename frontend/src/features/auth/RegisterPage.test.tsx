import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { ApiError } from "../../lib/api/client";
import RegisterPage from "./RegisterPage";
// @ts-expect-error - node types are excluded from tsconfig "types"; the
// import resolves at runtime under vitest (node environment).
import { readFileSync } from "node:fs";

// Mock only the auth hook; the real Field/Button/FormError components and the
// real validation + payload logic in RegisterPage stay under test.
const auth = vi.hoisted(() => ({ register: vi.fn() }));
vi.mock("../../auth/AuthProvider", () => ({
  useAuth: () => auth,
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/register"]}>
      <RegisterPage />
    </MemoryRouter>,
  );
}

function fillValidForm(email = "ada@example.com", password = "s3cure-pass") {
  fireEvent.change(screen.getByLabelText("Email"), { target: { value: email } });
  fireEvent.change(screen.getByLabelText("Password"), { target: { value: password } });
}

describe("registration page (immersive scene)", () => {
  it("renders the join heading, subtitle, and brand without the old split-screen", () => {
    renderPage();

    expect(
      screen.getByRole("heading", { level: 1, name: "Join Aegivault" }),
    ).toBeTruthy();
    expect(
      screen.getByText("Start governing production data and AI traffic securely."),
    ).toBeTruthy();
    expect(screen.getByText(/create your account/i)).toBeTruthy();
    expect(screen.getByRole("link", { name: "Aegivault home" })).toBeTruthy();
    // The old split-screen composition is gone: no photo panel, no ticker.
    expect(document.querySelector(".register-split")).toBeNull();
    expect(document.querySelector(".register-ticker")).toBeNull();
    expect(document.querySelector(".register-scene")).toBeTruthy();
  });

  it("renders six decorative holo panels around the card", () => {
    renderPage();

    expect(document.querySelectorAll('[data-testid="register-holo"]')).toHaveLength(6);
    for (const title of [
      "Your data. Your control.",
      "Sensitive Data Protection",
      "Audit & Evidence",
      "Policy Governance",
      "AI Security Gateway",
      "Governance Controls",
    ]) {
      expect(screen.getByText(title)).toBeTruthy();
    }
    // The real form is still present and labelled.
    expect(screen.getByLabelText("Email")).toBeTruthy();
    expect(screen.getByLabelText("Display name (optional)")).toBeTruthy();
    expect(screen.getByLabelText("Password")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Create account" })).toBeTruthy();
  });

  it("renders the real form fields, submit, and sign-in link", () => {
    renderPage();

    expect(screen.getByLabelText("Email")).toBeTruthy();
    expect(screen.getByLabelText("Display name (optional)")).toBeTruthy();
    expect(screen.getByLabelText("Password")).toBeTruthy();
    expect(screen.getByText("At least 8 characters.")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Sign in" }).getAttribute("href")).toBe(
      "/login",
    );
  });

  it("renders the form inside the elevated card with a security icon tile", () => {
    renderPage();

    const card = document.querySelector(".register-card");
    expect(card).toBeTruthy();
    expect(card?.querySelector("form")).toBeTruthy();
    expect(card?.querySelector(".register-card-mark")).toBeTruthy();
    // The icon tile is decorative: the heading still names the form.
    expect(card?.querySelector(".register-card-mark")?.getAttribute("aria-hidden")).toBe(
      "true",
    );
    expect(
      screen.getByRole("button", { name: "Create account" }).className,
    ).toContain("register-submit");
  });

  it("rejects an invalid email without calling the API", () => {
    auth.register.mockReset();
    renderPage();

    fillValidForm("not-an-email");
    fireEvent.click(screen.getByRole("button", { name: "Create account" }));

    expect(screen.getByRole("alert").textContent).toBe("Enter a valid email address.");
    expect(auth.register).not.toHaveBeenCalled();
  });

  it("rejects a short password without calling the API", () => {
    auth.register.mockReset();
    renderPage();

    fillValidForm("ada@example.com", "short");
    fireEvent.click(screen.getByRole("button", { name: "Create account" }));

    expect(screen.getByRole("alert").textContent).toBe(
      "Password must be at least 8 characters.",
    );
    expect(auth.register).not.toHaveBeenCalled();
  });

  it("submits the trimmed payload and omits a blank display name", async () => {
    auth.register.mockReset();
    auth.register.mockResolvedValue({ userId: "u-1" });
    renderPage();

    fillValidForm("  ada@example.com  ");
    fireEvent.click(screen.getByRole("button", { name: "Create account" }));

    expect(auth.register).toHaveBeenCalledTimes(1);
    expect(auth.register.mock.calls[0]?.[0]).toEqual({
      email: "ada@example.com",
      password: "s3cure-pass",
      displayName: undefined,
    });
  });

  it("trims and forwards a provided display name", async () => {
    auth.register.mockReset();
    auth.register.mockResolvedValue({ userId: "u-1" });
    renderPage();

    fillValidForm();
    fireEvent.change(screen.getByLabelText("Display name (optional)"), {
      target: { value: "  Ada  " },
    });
    fireEvent.click(screen.getByRole("button", { name: "Create account" }));

    expect(auth.register.mock.calls[0]?.[0]).toEqual({
      email: "ada@example.com",
      password: "s3cure-pass",
      displayName: "Ada",
    });
  });

  it("surfaces API errors without leaking details", async () => {
    auth.register.mockReset();
    auth.register.mockRejectedValue(new ApiError(409, "Email is already registered."));
    renderPage();

    fillValidForm();
    fireEvent.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.getByRole("alert").textContent).toBe("Email is already registered.");
  });

  it("disables the submit button and marks it busy while pending", async () => {
    auth.register.mockReset();
    let resolveRegister: (value: unknown) => void = () => {};
    auth.register.mockImplementation(
      () => new Promise((resolve) => void (resolveRegister = resolve)),
    );
    renderPage();

    fillValidForm();
    const submit = screen.getByRole("button", { name: "Create account" });
    fireEvent.click(submit);

    expect(submit.hasAttribute("disabled")).toBe(true);
    expect(submit.getAttribute("aria-busy")).toBe("true");

    resolveRegister({ userId: "u-1" });
    expect(await screen.findByRole("button", { name: "Create account" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Create account" }).hasAttribute("disabled")).toBe(
      false,
    );
  });

  it("shows the reset notice with a mailto link below the card", () => {
    renderPage();

    expect(screen.getByText(/Forgot your password\?/)).toBeTruthy();
    const mail = screen.getByRole("link", { name: "soubhagyabehera074@gmail.com" });
    expect(mail.getAttribute("href")).toBe("mailto:soubhagyabehera074@gmail.com");
    expect(document.querySelector(".register-support")).toBeTruthy();
  });

  it("hides the holo columns in the mobile stylesheet (cascade-safe)", () => {
    // Regression guard: register.css is injected after register-scene.css,
    // so its tablet `display: grid` rule wins at <=767px unless the mobile
    // block in this same file hides the columns again.
    const mobile = readFileSync(
      "src/features/auth/register.css",
      "utf8",
    ).split("@media (max-width: 767px)")[1];
    expect(mobile).toMatch(/\.register-column\s*\{\s*display:\s*none/);
  });

  it("keeps the mobile atmosphere restrained in the scene stylesheet", () => {
    const mobile = readFileSync(
      "src/features/auth/register-scene.css",
      "utf8",
    ).split("@media (max-width: 767px)")[1];
    expect(mobile).toMatch(/\.register-column\s*\{\s*display:\s*none/);
    expect(mobile).toMatch(/\.register-packets\s*\{\s*display:\s*none/);
  });
});
