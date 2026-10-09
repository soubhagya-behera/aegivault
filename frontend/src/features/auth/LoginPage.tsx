import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../../auth/AuthProvider";
import { ApiError } from "../../lib/api/client";
import Button from "../../components/ui/Button";
import Field from "../../components/ui/Field";
import AuthShell from "./AuthShell";
import FormError from "./FormError";

/**
 * Login page. Uses the real POST /api/auth/login via the auth provider.
 *
 * On success the provider marks the session authenticated and RedirectIfAuthed
 * sends the user to the protected workspace — no success is claimed here before
 * that state change. Errors are shown from the normalized, safe ApiError
 * message (never the entered password, never a stack trace).
 */
export default function LoginPage() {
  const { login } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const abortRef = useRef<AbortController | null>(null);

  // Cancel any in-flight request if the page unmounts.
  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    setError(null);

    if (email.trim() === "" || password === "") {
      setError("Enter your email and password.");
      return;
    }

    setPending(true);
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      await login({ email: email.trim(), password }, controller.signal);
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") return;
      setError(
        err instanceof ApiError
          ? err.message
          : "Unable to sign in. Please try again.",
      );
    } finally {
      if (abortRef.current === controller) abortRef.current = null;
      setPending(false);
    }
  }

  return (
    <AuthShell
      title="Sign in"
      subtitle="Access the Aegivault security workspace."
      footer={
        <>
          New here?{" "}
          <Link to="/register" className="text-accent underline hover:text-accent-hover">
            Create an account
          </Link>
        </>
      }
    >
      <form onSubmit={handleSubmit} noValidate className="flex flex-col gap-5">
        {error ? <FormError message={error} /> : null}
        <Field
          id="login-email"
          label="Email"
          type="email"
          name="email"
          autoComplete="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />
        <Field
          id="login-password"
          label="Password"
          type="password"
          name="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
        <Button type="submit" loading={pending} disabled={pending}>
          Sign in
        </Button>
      </form>
    </AuthShell>
  );
}
