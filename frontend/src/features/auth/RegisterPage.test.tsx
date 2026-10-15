import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { ApiError } from "../../lib/api/client";
import RegisterPage from "./RegisterPage";

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

describe("registration page (split-screen)", () => {
  it("renders the brand visual with the illustration and feature points", () => {
    renderPage();

    expect(
      screen.getByRole("heading", { level: 1, name: "Your data. Your control." }),
    ).toBeTruthy();
    for (const point of ["Privacy", "Governance", "Audit"]) {
      expect(screen.getByText(point)).toBeTruthy();
    }
    const image = screen.getByAltText(/glass security shield and padlock/i);
    expect(image.getAttribute("src")).toBe("/images/aegivault-register-hero-wide.png");
  });

  it("preserves the illustration without cropping or distortion", () => {
    renderPage();

    // The artwork must keep its intrinsic aspect ratio: width/height mirror
    // the 1448x1086 asset, and the contained presentation class rules out a
    // fixed-height cover crop of the shield and padlock.
    const image = screen.getByAltText(/glass security shield and padlock/i);
    expect(image.getAttribute("width")).toBe("1448");
    expect(image.getAttribute("height")).toBe("1086");
    expect(image.classList.contains("register-art-img")).toBe(true);
  });

  it("renders the real form fields, submit, and sign-in link", () => {
    renderPage();

    expect(
      screen.getByRole("heading", { level: 2, name: "Create your account" }),
    ).toBeTruthy();
    expect(screen.getByLabelText("Email")).toBeTruthy();
    expect(screen.getByLabelText("Display name (optional)")).toBeTruthy();
    expect(screen.getByLabelText("Password")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Create account" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Sign in" }).getAttribute("href")).toBe(
      "/login",
    );
  });

  it("keeps the illustration in its own stage, never behind the copy", () => {
    renderPage();

    // Layout contract for the desktop overlap bug: the artwork and the copy are
    // two separate sibling blocks inside .register-hero. The copy is therefore
    // never an ancestor of (and can never be laid over) the image, whatever the
    // viewport — the CSS stacks them in normal flow instead.
    const image = screen.getByAltText(/glass security shield and padlock/i);
    const stage = image.closest(".register-stage");
    expect(stage).toBeTruthy();
    expect(stage?.querySelector("img")).toBe(image);

    const copy = document.querySelector(".register-copy");
    expect(copy).toBeTruthy();
    expect(copy?.querySelector("img")).toBeNull();
    expect(copy?.contains(image)).toBe(false);
    expect(stage?.contains(copy)).toBe(false);
    expect(stage?.parentElement).toBe(copy?.parentElement);
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

  it("shows the ticker and reset notice inside the light panel only", () => {
    renderPage();

    const panel = document.querySelector(".register-form-panel");
    const visual = document.querySelector(".register-visual");
    expect(panel?.querySelector(".register-ticker")).toBeTruthy();
    expect(panel?.querySelector(".register-reset-note")).toBeTruthy();
    // Neither element may cross into the dark illustration panel.
    expect(visual?.querySelector(".register-ticker")).toBeNull();
    expect(visual?.querySelector(".register-reset-note")).toBeNull();
    expect(screen.getByText("Forgot your password?")).toBeTruthy();
    const mail = screen.getByRole("link", { name: "soubhagyabehera074@gmail.com" });
    expect(mail.getAttribute("href")).toBe("mailto:soubhagyabehera074@gmail.com");
  });
});
