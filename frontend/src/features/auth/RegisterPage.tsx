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
import { RegisterBackdrop, RegisterHoloColumn } from "./RegisterScene";
import "./register.css";

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MIN_PASSWORD_LENGTH = 8;

const RESET_EMAIL = "soubhagyabehera074@gmail.com";

/**
 * Registration page (immersive scene). Uses the real POST /api/auth/register
 * via the provider — fields, validation rules, and payload are unchanged.
 *
 * New accounts are always created with the backend's default role (USER); the
 * form exposes no role selection and never lets a user self-assign ADMIN.
 * Client-side validation is a convenience only — the backend remains
 * authoritative (e.g. duplicate email surfaces as a safe 409 error).
 *
 * Presentation: full-viewport security scene (atmosphere, orbit rings,
 * connection lines, six holographic panels) around a central floating
 * glass card with the real email/display-name/password form. Decorative
 * layers are pointer-events:none and aria-hidden; the form stays interactive.
 *
 * Motion is decorative and disabled under prefers-reduced-motion; the
 * form renders static and immediately in that case.
 */
export default function RegisterPage() {
  const { register } = useAuth();
  const reduce = useReducedMotion();
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

  // Decorative motion props; empty under reduced motion so content renders
  // static and immediately (also the path exercised in jsdom tests).
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
    <main className="register-scene">
      <RegisterBackdrop />

      <motion.header {...topEntry} className="register-top">
        <Link to="/" aria-label="Aegivault home" className="register-brand">
          <BrandMark className="size-6" />
          <span className="register-brand-name">Aegivault</span>
        </Link>
        <p className="register-descriptor">Data Privacy · Security · AI Governance</p>
      </motion.header>

      <div className="register-stage">
        <RegisterHoloColumn side="left" />

        <motion.section
          {...cardEntry}
          aria-labelledby="register-title"
          className="register-card-wrap"
        >
          <div className="register-card">
            <span aria-hidden="true" className="register-card-mark">
              <ShieldCheck className="size-5" />
            </span>
            <p className="register-eyebrow">Create your account</p>
            <h1 id="register-title" className="register-title">
              Join Aegivault
            </h1>
            <p className="register-subtitle">
              Start governing production data and AI traffic securely.
            </p>
            <div aria-hidden="true" className="register-rule" />
            <form onSubmit={handleSubmit} noValidate className="register-form">
              {error ? <FormError message={error} /> : null}
              <Field
                id="register-email"
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
                id="register-name"
                label="Display name (optional)"
                type="text"
                name="displayName"
                autoComplete="name"
                placeholder="Ada Lovelace"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
              />
              <Field
                id="register-password"
                label="Password"
                type="password"
                name="password"
                autoComplete="new-password"
                placeholder="Choose a strong password"
                hint={`At least ${MIN_PASSWORD_LENGTH} characters.`}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
              <Button type="submit" loading={pending} disabled={pending} className="register-submit">
                Create account
                <span aria-hidden="true" className="register-submit-arrow">
                  &rarr;
                </span>
              </Button>
            </form>
            <p className="register-footer">
              Already have an account?{" "}
              <Link to="/login" className="register-link">
                Sign in
              </Link>
            </p>
          </div>
        </motion.section>

        <RegisterHoloColumn side="right" />
      </div>

      <p aria-hidden="true" className="register-mobile-badge">
        <ShieldCheck className="size-4" />
        Private · Governed · Auditable
      </p>

      <motion.p {...noteEntry} className="register-support">
        Forgot your password? Password reset isn&apos;t available right now. For
        assistance, contact{" "}
        <a href={`mailto:${RESET_EMAIL}`}>{RESET_EMAIL}</a>.
      </motion.p>
    </main>
  );
}
