/**
 * Concentric-rings boot loader — vanilla-canvas port of the React
 * `loading-animation-1` component (no runtime deps in the shell bundle).
 */

export interface RingsOptions {
  size?: number;
  color?: string;
  rings?: number;
}

export function startRingsLoader(canvas: HTMLCanvasElement, opts: RingsOptions = {}): () => void {
  const size = opts.size ?? 120;
  const color = opts.color ?? "#c45c6a";
  const rings = opts.rings ?? 4;

  canvas.width = size;
  canvas.height = size;
  const ctx = canvas.getContext("2d");
  if (!ctx) return () => {};

  const centerX = size / 2;
  const centerY = size / 2;
  let time = 0;
  let raf = 0;

  const animate = (): void => {
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    for (let i = 0; i < rings; i++) {
      const baseRadius = size * 0.1 + i * (size * 0.15);
      const pulse = Math.sin(time * 0.03 - i * 0.5) * (size * 0.05);
      const radius = Math.min(baseRadius + pulse, size / 2 - 2);
      const opacity = 0.2 + Math.sin(time * 0.03 - i * 0.5) * 0.3;

      ctx.beginPath();
      ctx.arc(centerX, centerY, radius, 0, Math.PI * 2);
      ctx.strokeStyle = `${color}${Math.floor(opacity * 255).toString(16).padStart(2, "0")}`;
      ctx.lineWidth = 2;
      ctx.stroke();

      const numDots = 8;
      for (let j = 0; j < numDots; j++) {
        const angle = (j / numDots) * Math.PI * 2 + time * 0.02 * (i % 2 ? 1 : -1);
        const dotX = centerX + Math.cos(angle) * radius;
        const dotY = centerY + Math.sin(angle) * radius;
        ctx.beginPath();
        ctx.arc(dotX, dotY, 2, 0, Math.PI * 2);
        ctx.fillStyle = color;
        ctx.fill();
      }
    }

    // Center pulse.
    const centerPulse = Math.sin(time * 0.05) * 0.3 + 0.7;
    ctx.beginPath();
    ctx.arc(centerX, centerY, 5 * centerPulse, 0, Math.PI * 2);
    ctx.fillStyle = color;
    ctx.fill();

    time++;
    raf = requestAnimationFrame(animate);
  };

  animate();
  return () => cancelAnimationFrame(raf);
}
