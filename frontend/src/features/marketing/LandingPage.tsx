import LandingHero from "./LandingHero";

/**
 * Public landing page (Phase 3A) — navigation + hero only, by design.
 *
 * The section anchors are the contract with later phases:
 *   #platform  → feature showcase,  #workflow → scrollytelling sequence,
 *   #security  → security/audit section, plus the site footer.
 * None of those sections exist yet; this file only composes what is built.
 */
export default function LandingPage() {
  return <LandingHero />;
}
