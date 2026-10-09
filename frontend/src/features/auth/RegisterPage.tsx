import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../../auth/AuthProvider";
import { ApiError } from "../../lib/api/client";
import Button from "../../components/ui/Button";
import Field from "../../components/ui/Field";
import AuthShell from "./AuthShell";
import FormError from "./FormError";

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MIN_PASSWORD_LENGTH = 8;

/**
 * Registration page. Uses the real POST /api/auth/register via the provider.
 *
 * New accounts are always created with the backend's default role (USER); the
 * form exposes no role selection and never lets a user self-assign ADMIN.
 * Client-side validation is a convenience only — the backend remains
 * authoritative (e.g. duplicate email surfaces as a safe 409 error).
 */
export default function RegisterPage() {
  const { register } = useAuth();
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    setError(null);

    const trimmedEmail = email.trim();
    if (!EMAIL_PATTERN.test(trimmedEmail)) {
      setError("Enter a valid email address.");
      return;
    }
    if (password.length < MIN_PASSWORD_LENGTH) {
      setError(`Password must be at least ${MIN_PASSWORD_LENGTH} characters.`);
      return;
    }

    setPending(true);
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      await register(
        {
          email: trimmedEmail,
          password,
          displayName: displayName.trim() === "" ? undefined : displayName.trim(),
        },
        controller.signal,
      );
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") return;
      setError(
        err instanceof ApiError
          ? err.message
          : "Unable to create your account. Please try again.",
      );
    } finally {
      if (abortRef.current === controller) abortRef.current = null;
      setPending(false);
    }
  }

  return (
    <AuthShell
      title="Create your account"
      subtitle="Start governing production data and AI traffic."
      footer={
        <>
          Already have an account?{" "}
          <Link to="/login" className="text-accent underline hover:text-accent-hover">
            Sign in
          </Link>
        </>
      }
    >
      <form onSubmit={handleSubmit} noValidate className="flex flex-col gap-5">
        {error ? <FormError message={error} /> : null}
        <Field
          id="register-email"
          label="Email"
          type="email"
          name="email"
          autoComplete="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />
        <Field
          id="register-name"
          label="Display name (optional)"
          type="text"
          name="displayName"
          autoComplete="name"
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
        />
        <Field
          id="register-password"
          label="Password"
          type="password"
          name="password"
          autoComplete="new-password"
          hint={`At least ${MIN_PASSWORD_LENGTH} characters.`}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
        <Button type="submit" loading={pending} disabled={pending}>
          Create account
        </Button>
      </form>
    </AuthShell>
  );
}
