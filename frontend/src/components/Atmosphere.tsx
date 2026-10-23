import { DotPattern } from './DotPattern';

/**
 * Login-only static dot field. The authenticated console does not mount this
 * component, and the dot pattern itself has no motion.
 */
export function Atmosphere() {
  return (
    <div className="atmosphere" aria-hidden="true">
      <DotPattern className="atmosphere-dots" width={16} height={16} cr={1.1} />
    </div>
  );
}
