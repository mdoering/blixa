import type { CSSProperties } from 'react';

type BlixaVariant = 'header' | 'icon' | 'text' | 'main' | 'logo';

// Aspect ratios taken from the source SVG viewBoxes in public/brand/.
const RATIO: Record<BlixaVariant, number> = {
  header: 4167 / 1875,
  icon: 1,
  text: 4267 / 834,
  main: 1,
  logo: 1,
};

const SRC: Record<BlixaVariant, string> = {
  header: '/brand/blixa-header.svg',
  icon: '/brand/blixa-icon.svg',
  text: '/brand/blixa-text.svg',
  main: '/brand/blixa-main.svg',
  logo: '/brand/blixa-logo.svg',
};

export interface BlixaLogoProps {
  // header = full lockup (monogram + wordmark), icon = square monogram, text = wordmark only,
  // main = the larger monochrome brand mark used as the landing-page hero.
  variant?: BlixaVariant;
  // Rendered height in px; width is derived from the artwork's aspect ratio.
  height?: number;
  // Fill colour; defaults to currentColor so the mark inherits the surrounding text colour.
  color?: string;
  className?: string;
  style?: CSSProperties;
}

// The Blixa logo, drawn as a CSS mask filled with `color`. The artwork is single-colour, so masking
// with currentColor lets one asset work in both themes (white on the dark header, dark on light)
// without shipping per-theme SVGs. Source SVGs live in public/brand/ (served at /brand/*).
export default function BlixaLogo({
  variant = 'header',
  height = 28,
  color = 'currentColor',
  className,
  style,
}: BlixaLogoProps) {
  const width = Math.round(height * RATIO[variant]);
  const url = `url("${SRC[variant]}")`;
  return (
    <span
      role="img"
      aria-label="Blixa"
      className={className}
      style={{
        display: 'inline-block',
        flex: 'none',
        width,
        height,
        backgroundColor: color,
        WebkitMaskImage: url,
        WebkitMaskRepeat: 'no-repeat',
        WebkitMaskPosition: 'center',
        WebkitMaskSize: 'contain',
        maskImage: url,
        maskRepeat: 'no-repeat',
        maskPosition: 'center',
        maskSize: 'contain',
        ...style,
      }}
    />
  );
}
