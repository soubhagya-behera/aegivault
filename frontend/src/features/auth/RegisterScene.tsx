import { motion, useReducedMotion } from "motion/react";
import {
  ClipboardCheck,
  Database,
  ScrollText,
  Share2,
  ShieldCheck,
  Sparkles,
} from "lucide-react";
import { DUR, EASE, staggerChild, staggerContainer } from "../marketing/motion";
import "./register-scene.css";

export interface RegisterHoloItem {
  icon: typeof ShieldCheck;
  title: string;
  text: string;
  accent?: boolean;
}

export const REGISTER_LEFT_PANELS: RegisterHoloItem[] = [
  {
    icon: ShieldCheck,
    title: "Your data. Your control.",
    text: "Secure. Private. Auditable.",
  },
  {
    icon: Database,
    title: "Sensitive Data Protection",
    text: "Detect, mask, and anonymize sensitive data.",
  },
  {
    icon: ClipboardCheck,
    title: "Audit & Evidence",
    text: "Every action logged. Always verifiable.",
    accent: true,
  },
];

export const REGISTER_RIGHT_PANELS: RegisterHoloItem[] = [
  {
    icon: ScrollText,
    title: "Policy Governance",
    text: "Define rules. Assess impact.",
  },
  {
    icon: Sparkles,
    title: "AI Security Gateway",
    text: "Use production data safely with AI.",
  },
  {
    icon: Share2,
    title: "Governance Controls",
    text: "Privacy, security, and policy enforcement.",
  },
];

function RegisterHoloPanel({
  item,
  reduce,
}: {
  item: RegisterHoloItem;
  reduce: boolean;
}) {
  const Icon = item.icon;
  return (
    <motion.aside
      variants={reduce ? undefined : staggerChild}
      aria-hidden="true"
      data-testid="register-holo"
      className={`register-holo${item.accent ? " register-holo--accent" : ""}`}
    >
      <span className="register-holo-mark">
        <Icon className="size-4" />
      </span>
      <span className="register-holo-text">
        <span className="register-holo-title">{item.title}</span>
        <span className="register-holo-body">{item.text}</span>
      </span>
      <span className="register-holo-dot" />
    </motion.aside>
  );
}

export function RegisterHoloColumn({ side }: { side: "left" | "right" }) {
  const reduce = useReducedMotion();
  const items = side === "left" ? REGISTER_LEFT_PANELS : REGISTER_RIGHT_PANELS;
  return (
    <motion.div
      initial={reduce ? undefined : "hidden"}
      animate={reduce ? undefined : "visible"}
      variants={reduce ? undefined : staggerContainer(0.1, 0.3)}
      className={`register-column register-column--${side}`}
    >
      {items.map((item) => (
        <RegisterHoloPanel key={item.title} item={item} reduce={reduce === true} />
      ))}
    </motion.div>
  );
}

/** Connection paths from the side panels toward the central card. */
const REGISTER_LINKS = [
  "M110 130 C 250 150, 320 210, 395 265",
  "M110 350 C 250 350, 320 330, 395 330",
  "M110 570 C 250 550, 320 450, 395 395",
  "M890 130 C 750 150, 680 210, 605 265",
  "M890 350 C 750 350, 680 330, 605 330",
  "M890 570 C 750 550, 680 450, 605 395",
];

/**
 * Decorative atmosphere + orbital rings + connection lines + packets.
 * Purely presentational: pointer-events none, aria-hidden, no auth logic.
 * Packets and ring rotation render only when motion is allowed.
 */
export function RegisterBackdrop() {
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
    <div aria-hidden="true" className="register-backdrop">
      <div className="register-atmosphere" />
      <div className="register-dotfield" />
      <motion.svg
        {...fade}
        viewBox="0 0 1000 700"
        preserveAspectRatio="xMidYMid slice"
        className="register-orbits"
      >
        {/* Orbit rings around the card centre */}
        <g
          fill="none"
          stroke="rgba(102,133,255,0.26)"
          strokeWidth="1"
          className={reduce ? undefined : "register-ring-spin"}
        >
          <ellipse cx="500" cy="350" rx="320" ry="225" />
        </g>
        <g fill="none" stroke="rgba(102,133,255,0.15)" strokeWidth="1">
          <ellipse cx="500" cy="350" rx="405" ry="280" strokeDasharray="3 9" />
        </g>
        <ellipse
          cx="500"
          cy="350"
          rx="240"
          ry="165"
          fill="none"
          stroke="rgba(201,255,80,0.2)"
          strokeWidth="1"
          strokeDasharray="2 10"
          className={reduce ? undefined : "register-ring-drift"}
        />
        {/* Connection paths from panels toward the card */}
        {REGISTER_LINKS.map((d) => (
          <motion.path
            key={d}
            d={d}
            fill="none"
            stroke="url(#registerGlow)"
            strokeWidth="1.5"
            className="register-link-path"
            {...draw}
          />
        ))}
        <defs>
          <linearGradient id="registerGlow" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="#4169FF" stopOpacity="0" />
            <stop offset="55%" stopColor="#6685FF" stopOpacity="0.9" />
            <stop offset="100%" stopColor="#6685FF" stopOpacity="0" />
          </linearGradient>
        </defs>
        {!reduce ? (
          <g className="register-packets">
            <circle r="3" fill="#6685FF" opacity="0.9">
              <animateMotion dur="7s" repeatCount="indefinite" path={REGISTER_LINKS[0]} />
            </circle>
            <circle r="2.5" fill="#C9FF50" opacity="0.9">
              <animateMotion dur="9s" repeatCount="indefinite" path={REGISTER_LINKS[5]} />
            </circle>
            <circle r="2.5" fill="#6685FF" opacity="0.8">
              <animateMotion dur="8s" repeatCount="indefinite" path={REGISTER_LINKS[3]} />
            </circle>
            <circle r="2" fill="#8EA2FF" opacity="0.8">
              <animateMotion dur="10s" repeatCount="indefinite" path={REGISTER_LINKS[2]} />
            </circle>
          </g>
        ) : null}
      </motion.svg>
    </div>
  );
}
