import LandingHero from "./LandingHero";
import PlatformShowcase from "./PlatformShowcase";
import WorkflowSection from "./WorkflowSection";
import SecuritySection from "./SecuritySection";
import ConversionSection from "./ConversionSection";

/**
 * Public landing page (Phase 3D): five deliberate visual chapters —
 * opening (ink) → platform (paper) → workflow (moss) → evidence
 * (near-black) → conversion (paper). Each section owns its chapter scope
 * (data-mkt-chapter) so transitions read as one coherent narrative and the
 * authenticated dashboard is untouched. The site footer arrives later.
 */
export default function LandingPage() {
  return (
    <>
      <LandingHero />
      <PlatformShowcase />
      <WorkflowSection />
      <SecuritySection />
      <ConversionSection />
    </>
  );
}
