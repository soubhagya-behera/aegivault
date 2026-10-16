import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { Link } from "react-router-dom";
import { motion, useReducedMotion } from "motion/react";
import { ShieldCheck } from "lucide-react";
import { useAuth } from "../../auth/AuthProvider";
import { ApiError } from "../../lib/api/client";
import Button from "../../components/ui/Button";
import Field from "../../components/ui/Field";
import BrandMark from "../../components/ui/BrandMark";
import { DUR, EASE } from "../marketing/motion";
import FormError from "./FormError";
import { HoloColumn, LoginBackdrop } from "./LoginScene";
import "./login.css";

const RESET_EMAIL = "soubhagyabehera074@gmail.com";

/**
 * Immersive login scene. Authentication behavior is unchanged from the
 * original implementation: real POST /api/auth/login via the provider,
 * same payload, validation, error, loading, and redirect handling.
 *
 * Presentation: full-viewport security scene (atmosphere, orbit rings,
 * connection lines, four holographic panels) around a central floating
 * glass card with the real email/password form. Decorative layers are
 * pointer-events:none and aria-hidden; the form stays interactive.
 *
 * Motion is decorative and disabled under prefers-reduced-motion; the
 * form renders static and immediately in that case.
 */
export default function LoginPage() {
  const { login } = useAuth();
  const reduce = useReducedMotion();
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

  // Decorative entrance only; empty under reduced motion so content is
  // visible immediately (also the path exercised in jsdom tests).
  const topEntry = reduce
    ? {}
    : {
        initial: { opacity: 0, y: -10 },
        animate: { opacity: 1, y: 0 },
        transition: { duration: DUR.reveal, ease: EASE },
      };
  const cardEntry = reduce
    ? {}
    : {
        initial: { opacity: 0, scale: 0.97, y: 18 },
        animate: { opacity: 1, scale: 1, y: 0 },
        transition: { duration: DUR.hero, ease: EASE, delay: 0.1 },
      };
  const noteEntry = reduce
    ? {}
    : {
        initial: { opacity: 0 },
        animate: { opacity: 1 },
        transition: { duration: DUR.reveal, ease: EASE, delay: 0.5 },
      };

  return (
    <main className="login-scene">
      <LoginBackdrop />

      <motion.header {...topEntry} className="login-top">
        <Link to="/" aria-label="Aegivault home" className="login-brand">
          <BrandMark className="size-6" />
          <span className="login-brand-name">Aegivault</span>
        </Link>
        <p className="login-descriptor">Data Privacy · Security · AI Governance</p>
      </motion.header>

      <div className="login-stage">
        <HoloColumn side="left" />

        <motion.section
          {...cardEntry}
          aria-labelledby="login-title"
          className="login-card-wrap"
        >
          <div className="login-card">
            <span aria-hidden="true" className="login-card-mark">
              <ShieldCheck className="size-5" />
            </span>
            <p className="login-eyebrow">Welcome back</p>
            <h1 id="login-title" className="login-title">
              Welcome back
            </h1>
            <p className="login-subtitle">Sign in to your secure workspace.</p>
            <div aria-hidden="true" className="login-rule" />
            <form onSubmit={handleSubmit} noValidate className="login-form">
              {error ? <FormError message={error} /> : null}
              <Field
                id="login-email"
                label="Email"
                type="email"
                name="email"
                autoComplete="email"
                placeholder="you@company.com"
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
                placeholder="Enter your password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
              <Button type="submit" loading={pending} disabled={pending} className="login-submit">
                Sign in
              </Button>
            </form>
            <p className="login-footer">
              New to Aegivault?{" "}
              <Link to="/register" className="login-link">
                Create account
              </Link>
            </p>
          </div>
        </motion.section>

        <HoloColumn side="right" />
      </div>

      <p aria-hidden="true" className="login-mobile-badge">
        <ShieldCheck className="size-4" />
        Protected · Auditable · Governed
      </p>

      <motion.p {...noteEntry} className="login-support">
        Forgot your password? Password reset isn&apos;t available yet. Contact{" "}
        <a href={`mailto:${RESET_EMAIL}`}>{RESET_EMAIL}</a> for assistance.
      </motion.p>
    </main>
  );
}
