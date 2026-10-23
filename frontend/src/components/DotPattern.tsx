import { useId, type SVGProps } from 'react';

export interface DotPatternProps
  extends Omit<SVGProps<SVGSVGElement>, 'width' | 'height' | 'x' | 'y'> {
  /** Horizontal spacing between dots in pixels. */
  width?: number;
  /** Vertical spacing between dots in pixels. */
  height?: number;
  /** X offset of the whole pattern. */
  x?: number;
  /** Y offset of the whole pattern. */
  y?: number;
  /** X offset of an individual dot inside its cell. */
  cx?: number;
  /** Y offset of an individual dot inside its cell. */
  cy?: number;
  /** Radius of each dot. */
  cr?: number;
  /** Render a sparse layer of slowly glowing dots on top of the grid. */
  glow?: boolean;
  className?: string;
}

const GLOW_DOTS = [
  { x: '7%', y: '19%', r: 2.1 },
  { x: '21%', y: '64%', r: 1.6 },
  { x: '33%', y: '31%', r: 1.9 },
  { x: '46%', y: '78%', r: 1.5 },
  { x: '58%', y: '15%', r: 2.3 },
  { x: '67%', y: '52%', r: 1.7 },
  { x: '76%', y: '83%', r: 2 },
  { x: '84%', y: '28%', r: 1.6 },
  { x: '92%', y: '61%', r: 2.2 },
  { x: '14%', y: '91%', r: 1.4 },
  { x: '39%', y: '9%', r: 1.4 },
  { x: '71%', y: '95%', r: 1.5 },
] as const;

/**
 * An adapted version of Magic UI's DotPattern (v3) without Tailwind or a
 * motion dependency: the grid drifts slowly with a CSS transform and a sparse
 * layer of dots glows at staggered intervals. Animations are disabled by the
 * global `prefers-reduced-motion` rule.
 */
export function DotPattern({
  width = 24,
  height = 24,
  x = 0,
  y = 0,
  cx = 1,
  cy = 1,
  cr = 1,
  glow = false,
  className = '',
  ...props
}: DotPatternProps) {
  const rawId = useId();
  const id = `dot-${rawId.replace(/[^a-zA-Z0-9_-]/g, '')}`;
  const patternId = `${id}-pattern`;
  const glowId = `${id}-glow`;

  return (
    <svg
      aria-hidden="true"
      className={`dot-pattern ${className}`.trim()}
      {...props}
    >
      <defs>
        <pattern
          id={patternId}
          width={width}
          height={height}
          patternUnits="userSpaceOnUse"
          x={x}
          y={y}
        >
          <circle cx={cx} cy={cy} r={cr} fill="currentColor" />
        </pattern>
        <radialGradient id={glowId}>
          <stop offset="0%" stopColor="currentColor" stopOpacity="0.9" />
          <stop offset="100%" stopColor="currentColor" stopOpacity="0" />
        </radialGradient>
      </defs>

      <g className="dot-pattern-drift">
        <rect
          x={-width * 2}
          y={-height * 2}
          width="200%"
          height="200%"
          fill={`url(#${patternId})`}
        />
      </g>

      {glow && (
        <g className="dot-pattern-sparks">
          {GLOW_DOTS.map((dot, index) => (
            <circle
              key={index}
              className="dot-pattern-spark"
              cx={dot.x}
              cy={dot.y}
              r={dot.r}
              fill={`url(#${glowId})`}
            />
          ))}
        </g>
      )}
    </svg>
  );
}
