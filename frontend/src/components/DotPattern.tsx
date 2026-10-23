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
  className?: string;
}

/**
 * Static adaptation of Magic UI's DotPattern (v3) without Tailwind or motion:
 * one SVG pattern tile renders the dots and the oversized rect fills the
 * viewport. No animation is applied to the pattern.
 */
export function DotPattern({
  width = 16,
  height = 16,
  x = 0,
  y = 0,
  cx = 1,
  cy = 1,
  cr = 1.1,
  className = '',
  ...props
}: DotPatternProps) {
  const rawId = useId();
  const id = `dot-${rawId.replace(/[^a-zA-Z0-9_-]/g, '')}`;
  const patternId = `${id}-pattern`;

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
      </defs>
      <rect
        x={-width * 2}
        y={-height * 2}
        width="200%"
        height="200%"
        fill={`url(#${patternId})`}
      />
    </svg>
  );
}
