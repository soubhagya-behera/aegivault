import { motion, useReducedMotion } from "motion/react";
import type { ReactElement, ReactNode } from "react";
import type { FeatureId } from "./platformData";
import { useAccentFade, useStrokeDraw } from "./motion";

/**
 * Bespoke schematics per capability (Phase 3E): larger, bolder, cobalt-led
 * for the cool-white chapter. Pure SVG on semantic tokens; decorative
 * (`aria-hidden`) — panel text carries meaning, values are fictional
 * (`*.test`, `tok_av_…`). Motion demonstrates behavior: scan sweeps, token
 * morphs, inspect connectors, verdict resolution, chain pulse. Remounts per
 * feature so accents replay; static under reduced motion.
 */
const MUTED = "var(--color-text-muted)";
const LINE = "var(--color-border-strong)";
const ACCENT = "var(--color-accent)";
const TEXT = "var(--color-text)";
const SUCCESS = "var(--color-success)";
const DANGER = "var(--color-danger)";

interface LabelProps {
  x: number;
  y: number;
  children: string;
  fill?: string;
  size?: number;
}

function Label({ x, y, children, fill = MUTED, size = 10 }: LabelProps) {
  return (
    <text x={x} y={y} fill={fill} fontSize={size} letterSpacing="0.08em" fontFamily="var(--font-mono)">
      {children}
    </text>
  );
}

function Frame({ children }: { children: ReactNode }) {
  return (
    <svg viewBox="0 0 400 190" className="h-auto w-full" aria-hidden="true" fill="none">
      {children}
    </svg>
  );
}

function ProfileDiagram() {
  const emailDraw = useStrokeDraw(0.05);
  const nameDraw = useStrokeDraw(0.2);
  const scanFade = useAccentFade(0.3);
  return (
    <Frame>
      <motion.rect x="8" y="52" width="252" height="12" rx="2" fill={ACCENT} opacity={0.14} {...scanFade} />
      <rect x="8" y="20" width="252" height="158" rx="6" stroke={LINE} strokeWidth="1.5" />
      <line x1="8" y1="52" x2="260" y2="52" stroke={LINE} strokeWidth="1.5" />
      <Label x={20} y={42}>COLUMN</Label>
      <Label x={176} y={42}>FINDING</Label>
      <line x1="8" y1="88" x2="260" y2="88" stroke={LINE} />
      <line x1="8" y1="124" x2="260" y2="124" stroke={LINE} />
      <Label x={20} y={78} fill={TEXT} size={12}>contact</Label>
      <motion.rect x="172" y="62" width="66" height="20" rx="4" stroke={ACCENT} strokeWidth="2" {...emailDraw} />
      <text x="182" y="76" fill={ACCENT} fontSize={10} fontWeight="bold" fontFamily="var(--font-mono)">EMAIL</text>
      <Label x={20} y={114} fill={TEXT} size={12}>city</Label>
      <Label x={172} y={114}>— clear</Label>
      <Label x={20} y={150} fill={TEXT} size={12}>note</Label>
      <motion.rect x="172" y="134" width="58" height="20" rx="4" stroke={ACCENT} strokeWidth="2" {...nameDraw} />
      <text x="182" y="148" fill={ACCENT} fontSize={10} fontWeight="bold" fontFamily="var(--font-mono)">NAME</text>
      <Label x={278} y={42} fill={TEXT}>PROFILE</Label>
      <Label x={278} y={66}>per-column</Label>
      <Label x={278} y={82}>findings —</Label>
      <Label x={278} y={98}>rows never</Label>
      <Label x={278} y={114}>stored</Label>
    </Frame>
  );
}

function SanitizeDiagram() {
  const arrowDraw = useStrokeDraw(0.05);
  const planFade = useAccentFade(0.15);
  const tokenFade = useAccentFade(0.35);
  return (
    <Frame>
      <rect x="8" y="20" width="158" height="158" rx="6" stroke={LINE} strokeWidth="1.5" />
      <Label x={20} y={44}>BEFORE</Label>
      <line x1="8" y1="56" x2="166" y2="56" stroke={LINE} />
      <Label x={20} y={84} fill={TEXT} size={12}>contact</Label>
      <text x="20" y="112" fill={MUTED} fontSize={11} fontFamily="var(--font-mono)" textDecoration="line-through">user@example.test</text>
      <motion.line x1="166" y1="100" x2="222" y2="100" stroke={ACCENT} strokeWidth="2.5" {...arrowDraw} />
      <motion.path d="M 215 93 L 224 100 L 215 107" stroke={ACCENT} strokeWidth="2.5" {...planFade} />
      <motion.text x="172" y="88" fill={ACCENT} fontSize={10} fontWeight="bold" fontFamily="var(--font-mono)" {...planFade}>PLAN</motion.text>
      <rect x="230" y="20" width="162" height="158" rx="6" stroke={ACCENT} strokeWidth="2" />
      <Label x={242} y={44} fill={TEXT}>SANITIZED COPY</Label>
      <line x1="230" y1="56" x2="392" y2="56" stroke={LINE} />
      <Label x={242} y={84} fill={TEXT} size={12}>contact</Label>
      <motion.text x="242" y="112" fill={TEXT} fontSize={12} fontWeight="bold" fontFamily="var(--font-mono)" {...tokenFade}>tok_av_7f2c</motion.text>
      <Label x={242} y={150}>CSV ARTIFACT</Label>
    </Frame>
  );
}

function PostgresDiagram() {
  const boundDraw = useStrokeDraw(0.1);
  return (
    <Frame>
      <rect x="8" y="20" width="252" height="158" rx="6" stroke={LINE} strokeWidth="1.5" />
      <Label x={20} y={44}>SCHEMA public</Label>
      <line x1="8" y1="56" x2="260" y2="56" stroke={LINE} />
      <Label x={20} y={84} fill={TEXT} size={12}>customers</Label>
      <Label x={170} y={84}>TABLE</Label>
      <line x1="8" y1="100" x2="260" y2="100" stroke={LINE} />
      <Label x={20} y={128} fill={TEXT} size={12}>orders</Label>
      <motion.rect x="166" y="110" width="62" height="20" rx="4" stroke={ACCENT} strokeWidth="2" {...boundDraw} />
      <text x="175" y="124" fill={ACCENT} fontSize={10} fontWeight="bold" fontFamily="var(--font-mono)">BOUND</text>
      <line x1="8" y1="144" x2="260" y2="144" stroke={LINE} />
      <Label x={20} y={166} fill={TEXT} size={12}>events</Label>
      <Label x={170} y={166}>TABLE</Label>
      <Label x={278} y={44} fill={TEXT}>READ-ONLY</Label>
      <Label x={278} y={68}>discover →</Label>
      <Label x={278} y={84}>bind →</Label>
      <Label x={278} y={100}>profile</Label>
      <Label x={278} y={128}>source is</Label>
      <Label x={278} y={144}>untouched</Label>
    </Frame>
  );
}

function GatewayDiagram() {
  const inDraw = useStrokeDraw(0.05);
  const inHead = useAccentFade(0.15);
  const outDraw = useStrokeDraw(0.25);
  const allowFade = useAccentFade(0.4);
  const blockFade = useAccentFade(0.55);
  return (
    <Frame>
      <rect x="8" y="56" width="104" height="76" rx="6" stroke={LINE} strokeWidth="1.5" />
      <Label x={22} y={88}>AI-BOUND</Label>
      <Label x={22} y={106}>REQUEST</Label>
      <motion.line x1="112" y1="94" x2="156" y2="94" stroke={ACCENT} strokeWidth="2.5" {...inDraw} />
      <motion.path d="M 149 87 L 158 94 L 149 101" stroke={ACCENT} strokeWidth="2.5" {...inHead} />
      <rect x="162" y="62" width="64" height="64" rx="6" transform="rotate(45 194 94)" stroke={ACCENT} strokeWidth="2" />
      <text x="168" y="99" fill={TEXT} fontSize="10" fontWeight="bold" fontFamily="var(--font-mono)">INSPECT</text>
      <motion.line x1="232" y1="94" x2="262" y2="94" stroke={ACCENT} strokeWidth="2.5" {...outDraw} />
      <motion.rect x="270" y="40" width="122" height="40" rx="6" stroke={SUCCESS} strokeWidth="2" {...allowFade} />
      <text x="284" y="65" fill={SUCCESS} fontSize={12} fontWeight="bold" fontFamily="var(--font-mono)">ALLOW</text>
      <motion.rect x="270" y="104" width="122" height="40" rx="6" stroke={DANGER} strokeWidth="2" {...blockFade} />
      <text x="284" y="129" fill={DANGER} fontSize={12} fontWeight="bold" fontFamily="var(--font-mono)">BLOCK</text>
      <Label x={270} y={168}>REASON CODES — 200 DATA</Label>
    </Frame>
  );
}

function LimitsDiagram() {
  const budgetDraw = useStrokeDraw(0.1);
  const noteFade = useAccentFade(0.3);
  const usageFade = useAccentFade(0.2);
  return (
    <Frame>
      <rect x="8" y="20" width="104" height="68" rx="6" stroke={LINE} strokeWidth="1.5" />
      <Label x={22} y={50}>RATE</Label>
      <Label x={22} y={68}>LIMIT</Label>
      <line x1="112" y1="54" x2="138" y2="54" stroke={LINE} strokeWidth="1.5" />
      <path d="M 132 48 L 140 54 L 132 60" stroke={LINE} strokeWidth="1.5" />
      <rect x="144" y="20" width="108" height="68" rx="6" stroke={LINE} strokeWidth="1.5" />
      <Label x={158} y={50}>USAGE</Label>
      <Label x={158} y={68}>POLICY</Label>
      <line x1="252" y1="54" x2="272" y2="54" stroke={LINE} strokeWidth="1.5" />
      <path d="M 266 48 L 274 54 L 266 60" stroke={LINE} strokeWidth="1.5" />
      <motion.rect x="278" y="20" width="114" height="68" rx="6" stroke={ACCENT} strokeWidth="2.5" {...budgetDraw} />
      <text x="292" y="50" fill={TEXT} fontSize="11" fontWeight="bold" fontFamily="var(--font-mono)">TOKEN</text>
      <text x="292" y="68" fill={TEXT} fontSize="11" fontWeight="bold" fontFamily="var(--font-mono)">BUDGET</text>
      <Label x={8} y={114}>USAGE 68% · LIMIT 85% — AN ILLUSTRATIVE REQUEST</Label>
      <rect x="8" y="122" width="240" height="14" rx="3" stroke={LINE} />
      <motion.rect x="8" y="122" width="163" height="14" rx="3" fill={ACCENT} opacity={0.85} {...usageFade} />
      <motion.line x1="212" y1="118" x2="212" y2="140" stroke={TEXT} strokeWidth="2.5" {...budgetDraw} />
      <motion.text x="8" y="162" fill={MUTED} fontSize={10} fontFamily="var(--font-mono)" {...noteFade}>PER ACTOR — QUOTA IS NOT A SECURITY VERDICT</motion.text>
    </Frame>
  );
}

function AuditDiagram() {
  const reduce = useReducedMotion();
  const firstDraw = useStrokeDraw(0.05);
  const secondDraw = useStrokeDraw(0.25);
  const pulse = useAccentFade(0.45);
  return (
    <Frame>
      <rect x="8" y="52" width="100" height="72" rx="6" stroke={LINE} strokeWidth="1.5" />
      <Label x={24} y={82} fill={TEXT}>ENTRY</Label>
      <Label x={24} y={102}>k − 1</Label>
      <motion.line x1="108" y1="88" x2="144" y2="88" stroke={ACCENT} strokeWidth="2.5" {...firstDraw} />
      <rect x="150" y="52" width="100" height="72" rx="6" stroke={ACCENT} strokeWidth="2.5" />
      <Label x={166} y={82} fill={TEXT}>ENTRY</Label>
      <Label x={166} y={102}>k</Label>
      <motion.circle cx="200" cy="64" r="4" fill={ACCENT} {...pulse} />
      <motion.line x1="250" y1="88" x2="286" y2="88" stroke={ACCENT} strokeWidth="2.5" {...secondDraw} />
      {!reduce && (
        <motion.circle
          cx="108" cy="88" r="4" fill={ACCENT}
          initial={false}
          animate={{ cx: [108, 144, 250, 286, 108] }}
          transition={{ duration: 5, repeat: Infinity, ease: "easeInOut", times: [0, 0.2, 0.55, 0.75, 1] }}
        />
      )}
      <rect x="292" y="52" width="100" height="72" rx="6" stroke={LINE} strokeWidth="1.5" />
      <Label x={308} y={82} fill={TEXT}>ENTRY</Label>
      <Label x={308} y={102}>k + 1</Label>
      <Label x={8} y={34}>PREV-HASH LINK</Label>
      <Label x={8} y={152}>VERIFY: REPLAY, REPORT FIRST BROKEN LINK</Label>
    </Frame>
  );
}

const DIAGRAMS: Record<FeatureId, () => ReactElement> = {
  profile: ProfileDiagram,
  sanitize: SanitizeDiagram,
  postgres: PostgresDiagram,
  gateway: GatewayDiagram,
  limits: LimitsDiagram,
  audit: AuditDiagram,
};

export default function FeatureDiagram({ featureId }: { featureId: FeatureId }) {
  const Diagram = DIAGRAMS[featureId];
  // Remount per feature so the accent draws replay on every selection.
  return <Diagram key={featureId} />;
}
