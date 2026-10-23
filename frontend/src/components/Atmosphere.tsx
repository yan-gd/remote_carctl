import { DotPattern } from './DotPattern';

/**
 * Full-page animated dot field. No color wash or vignette: the page background
 * stays pure white in light mode (pure black in dark mode) and the pattern
 * covers the whole viewport.
 */
export function Atmosphere() {
  return (
    <div className="atmosphere" aria-hidden="true">
      <DotPattern className="atmosphere-dots" width={24} height={24} cr={1.2} glow />
    </div>
  );
}
