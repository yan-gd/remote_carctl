import { useEffect, useRef } from 'react';
import { DotPattern } from './DotPattern';

/**
 * Shared decorative login/dashboard field. Animation never enters React's render loop or
 * handles form events; the SVG remains a fallback when canvas is unavailable.
 */
export function Atmosphere() {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    const context = canvas?.getContext('2d');
    if (!canvas || !context) return;

    const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const theme = window.matchMedia('(prefers-color-scheme: dark)');
    const pointer = { x: -1000, y: -1000, targetX: -1000, targetY: -1000, strength: 0, active: false };
    let width = 0;
    let height = 0;
    let spacing = 20;
    let color = '';
    let frame = 0;
    let previous = -Infinity;
    let elapsed = 0;

    function draw(time: number) {
      context!.clearRect(0, 0, width, height);
      context!.fillStyle = color;
      const still = motion.matches;
      pointer.x += (pointer.targetX - pointer.x) * 0.12;
      pointer.y += (pointer.targetY - pointer.y) * 0.12;
      pointer.strength += ((pointer.active ? 1 : 0) - pointer.strength) * 0.08;

      for (let y = -spacing; y < height + spacing; y += spacing) {
        for (let x = -spacing; x < width + spacing; x += spacing) {
          const wave = still ? 0 : Math.sin(x * 0.008 + y * 0.006 - time * 0.55);
          const cross = still ? 0 : Math.cos(y * 0.012 - x * 0.004 + time * 0.36);
          let px = x + wave * 5;
          let py = y + cross * 6 + wave * 3;
          let influence = 0;

          if (!still && pointer.strength > 0.001) {
            const dx = px - pointer.x;
            const dy = py - pointer.y;
            const distance = Math.hypot(dx, dy);
            influence = Math.max(0, 1 - distance / 220) * pointer.strength;
            const ripple = Math.sin(distance * 0.042 - time * 2.8) * 10;
            const force = influence * influence * (26 + ripple);
            px += dx / Math.max(distance, 1) * force;
            py += dy / Math.max(distance, 1) * force;
          }

          context!.globalAlpha = still ? 0.65 : 0.46 + (wave + 1) * 0.17 + influence * 0.2;
          context!.beginPath();
          context!.arc(px, py, still ? 1 : 0.85 + (wave + 1) * 0.22 + influence * 0.45, 0, Math.PI * 2);
          context!.fill();
        }
      }
      context!.globalAlpha = 1;
    }

    function tick(now: number) {
      // Cap the decorative work at 30 fps; stop entirely in a hidden tab.
      if (now - previous >= 1000 / 30) {
        elapsed += Math.min(now - previous, 50) / 1000;
        previous = now;
        draw(elapsed);
      }
      frame = window.requestAnimationFrame(tick);
    }

    function sync() {
      window.cancelAnimationFrame(frame);
      previous = -Infinity;
      color = getComputedStyle(canvas!).getPropertyValue('--dot-color').trim();
      canvas!.dataset.motion = motion.matches ? 'reduced' : 'flowing';
      draw(motion.matches ? 0 : elapsed);
      if (!motion.matches && !document.hidden) frame = window.requestAnimationFrame(tick);
    }

    function resize() {
      width = window.innerWidth;
      height = window.innerHeight;
      // Bound work on very large displays without lowering retina sharpness.
      spacing = Math.max(20, Math.sqrt(width * height / 6500));
      const ratio = Math.min(window.devicePixelRatio || 1, 2);
      canvas!.width = Math.round(width * ratio);
      canvas!.height = Math.round(height * ratio);
      context!.setTransform(ratio, 0, 0, ratio, 0, 0);
      canvas!.parentElement!.dataset.ready = 'true';
      sync();
    }

    function move(event: PointerEvent) {
      if (event.pointerType === 'touch' || motion.matches) return;
      if (!pointer.active) {
        pointer.x = event.clientX;
        pointer.y = event.clientY;
      }
      pointer.targetX = event.clientX;
      pointer.targetY = event.clientY;
      pointer.active = true;
    }

    function leave(event?: PointerEvent) {
      if (!event || event.relatedTarget === null) pointer.active = false;
    }

    const blur = () => leave();

    resize();
    window.addEventListener('resize', resize);
    window.addEventListener('pointermove', move, { passive: true });
    window.addEventListener('pointerout', leave);
    window.addEventListener('blur', blur);
    document.addEventListener('visibilitychange', sync);
    motion.addEventListener('change', sync);
    theme.addEventListener('change', sync);

    return () => {
      window.cancelAnimationFrame(frame);
      window.removeEventListener('resize', resize);
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerout', leave);
      window.removeEventListener('blur', blur);
      document.removeEventListener('visibilitychange', sync);
      motion.removeEventListener('change', sync);
      theme.removeEventListener('change', sync);
    };
  }, []);

  return (
    <div className="atmosphere" aria-hidden="true">
      <DotPattern className="atmosphere-dots atmosphere-fallback" width={20} height={20} cr={1.1} />
      <canvas ref={canvasRef} className="atmosphere-dots atmosphere-canvas" />
    </div>
  );
}
