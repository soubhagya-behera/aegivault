import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { Link } from "react-router-dom";
import { motion, useReducedMotion } from "motion/react";
import { ScrollText, ShieldCheck, SlidersHorizontal } from "lucide-react";
import { useAuth } from "../../auth/AuthProvider";
import { ApiError } from "../../lib/api/client";
import Button from "../../components/ui/Button";
import Field from "../../components/ui/Field";
import BrandMark from "../../components/ui/BrandMark";
import { DUR, EASE, staggerChild, staggerContainer } from "../marketing/motion";
import FormError from "./FormError";
import "./register.css";
import "./register-panel.css";

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MIN_PASSWORD_LENGTH = 8;

const HERO_SRC = "/images/aegivault-register-hero-wide.png";
const HERO_ALT =
  "Illustration of a glass security shield and padlock surrounded by governed data symbols";
/** Intrinsic asset dimensions (1448x1086 landscape); mirrored on the img so the
 *  browser reserves the exact box before the file decodes (no layout shift). */
const HERO_WIDTH = 1448;
const HERO_HEIGHT = 1086;

/** Concise product points; mirrors the landing-page narrative, no new claims. */
const HIGHLIGHTS = [
  {
    icon: ShieldCheck,
    title: "Privacy",
    text: "Profile sensitive data under explicit policy.",
  },
  {
    icon: SlidersHorizontal,
    title: "Governance",
    text: "Control every AI-bound request.",
  },
  {
    icon: ScrollText,
    title: "Audit",
    text: "Keep evidence that verifies.",
  },
];

/**
 * Registration page (split-screen). Uses the real POST /api/auth/register via
 * the provider — fields, validation rules, and payload are unchanged.
 *
 * New accounts are always created with the backend's default role (USER); the
 * form exposes no role selection and never lets a user self-assign ADMIN.
 * Client-side validation is a convenience only — the backend remains
 * authoritative (e.g. duplicate email surfaces as a safe 409 error).
 *
 * COMPOSITION (all of it is in normal flow — no absolute positioning and no
 * negative margins are used to place content):
 *   • Desktop: brand → dedicated visual stage → eyebrow → headline → one-line
 *     support sentence → three compact highlights. The illustration lives in a
 *     dedicated, aspect-ratio-aware box, so it can never sit under the copy.
 *   • Mobile (<768px): brand above a full-width illustration only. The
 *     entire copy block stays in the DOM but is hidden by CSS there
 *     (restored on desktop) to keep the dark hero compact.
 *
 * Motion is decorative: a short fade/scale on the illustration, a small ambient
 * float, a staggered copy reveal, and a fade-up on the form card. All of it is
 * disabled under prefers-reduced-motion, and registration works either way.
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
  const imageEntry = reduce
    ? {}
    : {
        initial: { opacity: 0, scale: 0.96, y: 10 },
        animate: { opacity: 1, scale: 1, y: 0 },
        transition: { duration: DUR.hero, ease: EASE },
      };
  // Small amplitude on purpose: the stage holds a block gap above and below it,
  // so the illustration travels inside its own region and never reaches text.
  const imageFloat = reduce
    ? {}
    : {
        animate: { y: [0, -6, 0] },
        transition: { duration: 7, repeat: Infinity, ease: "easeInOut" as const },
      };
  const copySequence = reduce
    ? {}
    : { initial: "hidden" as const, animate: "visible" as const, variants: staggerContainer(0.07, 0.12) };
  const formReveal = reduce
    ? {}
    : {
        initial: { opacity: 0, y: 22, rotateX: 5 },
        animate: { opacity: 1, y: 0, rotateX: 0 },
        transition: { duration: DUR.reveal, ease: EASE, delay: 0.1 },
      };

  return (
    <main className="register-split">
      {/* Left panel: dark brand visual. Brand, then the illustration in its own
          stage, then the copy — stacked in normal flow at every width. */}
      <section aria-labelledby="register-visual-title" className="register-visual">
        <Link to="/" aria-label="Aegivault home" className="register-brand">
          <BrandMark className="size-6 text-text" />
          <span className="register-brand-name">Aegivault</span>
        </Link>

        <div className="register-hero">
          <div className="register-stage">
            <motion.div {...imageEntry} className="register-stage-inner">
              <motion.div {...imageFloat} className="h-full">
                <img
                  src={HERO_SRC}
                  alt={HERO_ALT}
                  width={HERO_WIDTH}
                  height={HERO_HEIGHT}
                  decoding="async"
                  fetchPriority="high"
                  className="register-art-img"
                />
              </motion.div>
            </motion.div>
          </div>

          <motion.div {...copySequence} className="register-copy">
            <motion.p
              variants={reduce ? undefined : staggerChild}
              className="register-eyebrow"
            >
              DATA PRIVACY / SECURITY / AI GOVERNANCE
            </motion.p>
            <motion.h1
              variants={reduce ? undefined : staggerChild}
              id="register-visual-title"
              className="register-headline"
            >
              Your data. Your control.
            </motion.h1>
            <motion.p
              variants={reduce ? undefined : staggerChild}
              className="register-lede"
            >
              Inspect sensitive data, govern AI-bound requests, and keep
              auditable evidence.
            </motion.p>
            <motion.ul
              variants={reduce ? undefined : staggerChild}
              className="register-highlights"
            >
              {HIGHLIGHTS.map((item) => (
                <li key={item.title} className="register-highlight">
                  <span aria-hidden="true" className="register-highlight-mark">
                    <item.icon className="size-4" />
                  </span>
                  <span className="register-highlight-text">
                    <span className="register-highlight-title">{item.title}</span>
                    <span className="register-highlight-body">{item.text}</span>
                  </span>
                </li>
              ))}
            </motion.ul>
          </motion.div>
        </div>
      </section>

      {/* Right panel: light registration form on the ambient panel surface. */}
      <section aria-labelledby="register-title" className="register-form-panel">
        <motion.div {...formReveal} className="register-card">
          <span aria-hidden="true" className="register-card-mark">
            <ShieldCheck className="size-5" />
          </span>
          <h2 id="register-title" className="register-card-title">
            Create your account
          </h2>
          <p className="register-card-subtitle">
            Start governing production data and AI traffic.
          </p>
          <div aria-hidden="true" className="register-card-rule" />
          <form onSubmit={handleSubmit} noValidate className="register-form">
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
            <Button type="submit" loading={pending} disabled={pending} className="register-submit">
              Create account
            </Button>
          </form>
          <p className="register-card-footer">
            Already have an account?{" "}
            <Link to="/login" className="register-signin">
              Sign in
            </Link>
          </p>
        </motion.div>
      </section>
    </main>
  );
}