import { motion, useReducedMotion } from "motion/react";
import {
  ClipboardCheck,
  Database,
  ScrollText,
  ShieldCheck,
} from "lucide-react";
import { DUR, EASE, staggerChild, staggerContainer } from "../marketing/motion";
import "./login-scene.css";

export interface HoloItem {
  icon: typeof ShieldCheck;
  title: string;
  text: string;
  accent?: boolean;
}

export const LEFT_PANELS: HoloItem[] = [
  {
    icon: ShieldCheck,
    title: "Your data. Your control.",
    text: "Secure. Private. Auditable.",
  },
  {
    icon: Database,
    title: "Protected Data",
    text: "From production to AI, with control.",
  },
];

export const RIGHT_PANELS: HoloItem[] = [
  {
    icon: ScrollText,
    title: "Policy Governance",
    text: "Understand changes. Assess impact.",
  },
  {
    icon: ClipboardCheck,
    title: "Audit & Evidence",
    text: "Every action recorded. Always verifiable.",
    accent: true,
  },
];

function HoloPanel({ item, reduce }: { item: HoloItem; reduce: boolean }) {
  const Icon = item.icon;
  return (
    <motion.aside
      variants={reduce ? undefined : staggerChild}
      aria-hidden="true"
      data-testid="login-holo"
      className={`login-holo${item.accent ? " login-holo--accent" : ""}`}
    >
      <span className="login-holo-mark">
        <Icon className="size-4" />
      </span>
      <span className="login-holo-text">
        <span className="login-holo-title">{item.title}</span>
        <span className="login-holo-body">{item.text}</span>
      </span>
      <span className="login-holo-dot" />
    </motion.aside>
  );
}

export function HoloColumn({ side }: { side: "left" | "right" }) {
  const reduce = useReducedMotion();
  const items = side === "left" ? LEFT_PANELS : RIGHT_PANELS;
  return (
    <motion.div
      initial={reduce ? undefined : "hidden"}
      animate={reduce ? undefined : "visible"}
      variants={reduce ? undefined : staggerContainer(0.12, 0.35)}
      className={`login-column login-column--${side}`}
    >
      {items.map((item) => (
        <HoloPanel key={item.title} item={item} reduce={reduce === true} />
      ))}
    </motion.div>
  );
}

/**
 * Decorative atmosphere + orbital rings + connection lines + particles.
 * Purely presentational: pointer-events none, aria-hidden, no auth logic.
 * Particles and ring rotation render only when motion is allowed.
 */
export function LoginBackdrop() {
  const reduce = useReducedMotion();
  const draw = reduce
    ? {}
    : {
        initial: { opacity: 0, pathLength: 0 },
        animate: { opacity: 1, pathLength: 1 },
        transition: { duration: 1.1, delay: 0.35, ease: EASE },
      };
  const fade = reduce
    ? {}
    : {
        initial: { opacity: 0 },
        animate: { opacity: 1 },
        transition: { duration: DUR.hero, ease: EASE },
      };

  return (
    <div aria-hidden="true" className="login-backdrop">
      <div className="login-atmosphere" />
      <div className="login-dotfield" />
      <motion.svg
        {...fade}
        viewBox="0 0 1000 640"
        preserveAspectRatio="xMidYMid slice"
        className="login-orbits"
      >
        {/* Orbit rings around the card centre */}
        <g
          fill="none"
          stroke="rgba(102,133,255,0.28)"
          strokeWidth="1"
          className={reduce ? undefined : "login-ring-spin"}
        >
          <ellipse cx="500" cy="320" rx="300" ry="200" />
        </g>
        <g fill="none" stroke="rgba(102,133,255,0.16)" strokeWidth="1">
          <ellipse cx="500" cy="320" rx="380" ry="252" strokeDasharray="3 9" />
        </g>
        <ellipse
          cx="500"
          cy="320"
          rx="225"
          ry="150"
          fill="none"
          stroke="rgba(201,255,80,0.22)"
          strokeWidth="1"
          strokeDasharray="2 10"
          className={reduce ? undefined : "login-ring-drift"}
        />
        {/* Connection paths from panels toward the card */}
        <motion.path
          d="M120 170 C 260 190, 330 250, 400 300"
          fill="none"
          stroke="url(#loginGlow)"
          strokeWidth="1.5"
          {...draw}
        />
        <motion.path
          d="M120 480 C 250 460, 330 400, 400 348"
          fill="none"
          stroke="url(#loginGlow)"
          strokeWidth="1.5"
          {...draw}
        />
        <motion.path
          d="M880 170 C 740 190, 670 250, 600 300"
          fill="none"
          stroke="url(#loginGlow)"
          strokeWidth="1.5"
          {...draw}
        />
        <motion.path
          d="M880 480 C 750 460, 670 400, 600 348"
          fill="none"
          stroke="url(#loginGlow)"
          strokeWidth="1.5"
          {...draw}
        />
        <defs>
          <linearGradient id="loginGlow" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="#4169FF" stopOpacity="0" />
            <stop offset="55%" stopColor="#6685FF" stopOpacity="0.9" />
            <stop offset="100%" stopColor="#6685FF" stopOpacity="0" />
          </linearGradient>
        </defs>
        {!reduce ? (
          <g>
            <circle r="3" fill="#6685FF" opacity="0.9">
              <animateMotion dur="7s" repeatCount="indefinite" path="M120 170 C 260 190, 330 250, 400 300" />
            </circle>
            <circle r="2.5" fill="#C9FF50" opacity="0.9">
              <animateMotion dur="9s" repeatCount="indefinite" path="M880 480 C 750 460, 670 400, 600 348" />
            </circle>
            <circle r="2.5" fill="#6685FF" opacity="0.8">
              <animateMotion dur="8s" repeatCount="indefinite" path="M880 170 C 740 190, 670 250, 600 300" />
            </circle>
          </g>
        ) : null}
      </motion.svg>
    </div>
  );
}
