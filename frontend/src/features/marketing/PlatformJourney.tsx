import { useCallback, useEffect, useRef, useState } from "react";
import { useReducedMotion } from "motion/react";
import { FEATURES } from "./platformData";
import JourneyPanel from "./JourneyPanel";
import CapabilityRail from "./CapabilityRail";
import CarouselNav from "./CarouselNav";
import { carouselIndexForScroll } from "./motion";

/**
 * Manual horizontal capability carousel (Chapter 02 rebuild).
 *
 * One native `overflow-x: auto` track holds all six capability cards.
 * Vertical page scrolling never moves the cards: there is no `useScroll`,
 * no `useTransform`, no `window.scrollY` reader, and no pinned/sticky
 * runway in this component. Horizontal movement comes only from deliberate
 * interaction — touch swipe, native horizontal scroll, pointer drag, rail
 * selection, or the previous/next buttons.
 */
export default function PlatformJourney() {
  const reduce = useReducedMotion();
  const trackRef = useRef<HTMLDivElement | null>(null);
  const railRef = useRef<HTMLDivElement | null>(null);
  const [active, setActive] = useState(0);
  const activeRef = useRef(0);
  // Programmatic navigation (rail/arrows) sets the index immediately, then
  // the browser animates/scrolls the track toward the card. Scroll events
  // fired during that motion report intermediate positions that must not
  // overwrite the already-correct selection — the lock holds them off until
  // the track settles near the target (or a timeout expires). Manual swipes
  // never set the lock, so they always drive the index.
  const programmaticRef = useRef<{ index: number; expires: number } | null>(null);

  // The ONLY scroll reader: the track's own horizontal scrollLeft. No
  // window/page listener exists in this component, so vertical page
  // scrolling cannot move the cards. Card starts come from `offsetLeft`
  // (track-relative layout coordinates), never from viewport rects, so the
  // comparison against `scrollLeft` always uses one coordinate system.
  // Updates are rAF-throttled and only set state when the nearest card
  // changes.
  const syncFromTrack = useCallback(() => {
    const track = trackRef.current;
    if (!track) return;
    const cards = Array.from(track.querySelectorAll<HTMLElement>(".mkt-carousel-card"));
    if (cards.length === 0) return;
    const starts = cards.map((card) => card.offsetLeft);
    const maxScroll = track.scrollWidth - track.clientWidth;
    // A programmatic selection already named the target: hold it until the
    // track settles within half a card stride of the target start (or the
    // target is the last card and the track hits its maximum offset).
    const programmatic = programmaticRef.current;
    if (programmatic !== null) {
      if (Date.now() > programmatic.expires) {
        programmaticRef.current = null;
      } else {
        const targetStart = starts[programmatic.index] ?? 0;
        const stride =
          cards.length > 1 ? Math.abs((starts[1] ?? 0) - (starts[0] ?? 0)) || 1 : 1;
        const settled =
          Math.abs(track.scrollLeft - targetStart) <= stride / 2 ||
          (programmatic.index === cards.length - 1 && track.scrollLeft >= maxScroll - 4);
        if (settled) programmaticRef.current = null;
        else return;
      }
    }
    const next = carouselIndexForScroll(starts, track.scrollLeft, maxScroll);
    if (next !== activeRef.current) {
      activeRef.current = next;
      setActive(next);
    }
  }, []);

  useEffect(() => {
    const track = trackRef.current;
    if (!track) return;
    let debounceId: number | undefined;
    // Debounced sync performed after scroll events stop; no per-frame state
    // updates. The rAF version above delayed state too long and made manual
    // track scrolling feel unresponsive in tests and browsers.
    const scheduleSync = () => {
      if (debounceId !== undefined) return;
      debounceId = window.setTimeout(() => {
        debounceId = undefined;
        syncFromTrack();
      }, 60);
    };
    function onScroll() {
      scheduleSync();
    }
    syncFromTrack();
    track.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", syncFromTrack);
    return () => {
      track.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", syncFromTrack);
      if (debounceId !== undefined) window.clearTimeout(debounceId);
    };
  }, [syncFromTrack]);

  // Container-local navigation: scrolls ONLY the carousel track via
  // `track.scrollTo` — `scrollIntoView` is never used here because it can
  // scroll every ancestor including the document, which shifted the whole
  // page sideways and exposed the dark canvas as a "black screen".
  function scrollCardIntoView(index: number) {
    const track = trackRef.current;
    const card = track?.querySelectorAll<HTMLElement>(".mkt-carousel-card")[index];
    if (!track || !card) return;
    const behavior = reduce ? "auto" : "smooth";
    // `offsetLeft` is track-relative; subtracting the track's own left
    // padding lands the card flush with the scroll origin.
    const style = window.getComputedStyle(track);
    const paddingLeft = Number.parseFloat(style.paddingLeft || "") || 0;
    const target = Math.max(0, card.offsetLeft - paddingLeft);
    track.scrollTo({ left: target, behavior });
  }

  function goTo(index: number) {
    const clamped = Math.min(FEATURES.length - 1, Math.max(0, index));
    activeRef.current = clamped;
    setActive(clamped);
    // Hold scroll-sync off until the track settles at the new target, so
    // intermediate scroll events can't drag the index back mid-flight.
    programmaticRef.current = { index: clamped, expires: Date.now() + 1200 };
    scrollCardIntoView(clamped);
  }

  // Rail follows the active tab with a rail-container-local scroll: the
  // active button is brought into view inside the rail's own box via
  // `rail.scrollTo`, which never moves the document or any other ancestor.
  // Manual rail swiping still works — this only recenters after selection.
  useEffect(() => {
    const rail = railRef.current;
    if (!rail) return;
    const buttons = rail.querySelectorAll<HTMLElement>("[data-rail-tab]");
    const button = buttons[active];
    if (!button) return;
    const target = Math.max(
      0,
      Math.min(
        rail.scrollWidth - rail.clientWidth,
        button.offsetLeft - rail.clientWidth / 2 + button.clientWidth / 2,
      ),
    );
    const behavior = reduce ? "auto" : "smooth";
    // Skip no-op scrolls so programmatic rail motion never fights a user
    // mid-swipe.
    if (Math.abs(rail.scrollLeft - target) > 2) {
      rail.scrollTo({ left: target, behavior });
    }
  }, [active, reduce]);

  // Pointer drag: press-and-drag scrolls the native track horizontally.
  // Vertical movement is ignored, and native touch scrolling is untouched.
  useEffect(() => {
    const track = trackRef.current;
    if (!track || reduce) return;
    let dragging = false;
    let startX = 0;
    let startLeft = 0;
    function onDown(event: PointerEvent) {
      if (event.pointerType !== "mouse" && event.pointerType !== "pen") return;
      if (event.button !== 0) return;
      dragging = true;
      startX = event.clientX;
      startLeft = track?.scrollLeft ?? 0;
      track?.setPointerCapture?.(event.pointerId);
    }
    function onMove(event: PointerEvent) {
      if (!dragging || !track) return;
      track.scrollLeft = startLeft - (event.clientX - startX);
    }
    function onUp() {
      dragging = false;
    }
    track.addEventListener("pointerdown", onDown);
    track.addEventListener("pointermove", onMove);
    track.addEventListener("pointerup", onUp);
    track.addEventListener("pointercancel", onUp);
    return () => {
      track.removeEventListener("pointerdown", onDown);
      track.removeEventListener("pointermove", onMove);
      track.removeEventListener("pointerup", onUp);
      track.removeEventListener("pointercancel", onUp);
    };
  }, [reduce]);

  const current = FEATURES[active] ?? FEATURES[0];

  return (
    <div className="mx-auto w-full min-w-0 max-w-app px-6 pb-8 pt-2 md:pb-12">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <p className="font-mono text-xs uppercase tracking-[0.16em] text-text-muted">
          Swipe, scroll sideways, or use the arrows
        </p>
        <p className="hidden font-mono text-xs uppercase tracking-[0.16em] text-text-muted sm:block">
          {current?.title}
        </p>
      </div>

      <CapabilityRail active={active} onSelect={goTo} railRef={railRef} />

      <div
        ref={trackRef}
        role="region"
        aria-roledescription="carousel"
        aria-label="Platform capabilities carousel"
        tabIndex={0}
        className="mkt-carousel-track mt-4"
      >
        {FEATURES.map((feature, index) => (
          <JourneyPanel
            key={feature.id}
            feature={feature}
            index={index}
            count={FEATURES.length}
            active={index === active}
          />
        ))}
      </div>

      <CarouselNav
        active={active}
        count={FEATURES.length}
        canPrev={active > 0}
        canNext={active < FEATURES.length - 1}
        onPrev={() => goTo(active - 1)}
        onNext={() => goTo(active + 1)}
      />
    </div>
  );
}
