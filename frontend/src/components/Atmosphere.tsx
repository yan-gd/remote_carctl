import { DotPattern } from './DotPattern';

/**
 * Login-only dot field. The grid is denser than the default DotPattern, and
 * three expanding ripple rings plus a slow breathing scale add the wave
 * animation. The authenticated console does not mount this component.
 */
export function Atmosphere() {
  return (
    <div className="atmosphere" aria-hidden="true">
      <DotPattern className="atmosphere-dots" width={16} height={16} cr={1.1} glow />
      <span className="atmosphere-wave" />
      <span className="atmosphere-wave" />
      <span className="atmosphere-wave" />
    </div>
  );
}
