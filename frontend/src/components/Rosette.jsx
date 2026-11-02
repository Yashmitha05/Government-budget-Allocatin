/**
 * Guilloche rosette, after the engraved medallions on banknotes and stamp paper.
 * Rings of rotated ellipses; drawn in a single colour at low opacity.
 */
export default function Rosette({ className, color = '#ffffff', opacity = 0.1, rings = [[44, 18, 36], [32, 12, 30], [20, 7, 24]] }) {
  return (
    <svg className={className} viewBox="-50 -50 100 100" aria-hidden="true" focusable="false">
      <g fill="none" stroke={color} strokeOpacity={opacity} strokeWidth="0.35">
        {rings.map(([rx, ry, n]) => Array.from({ length: n }, (_, i) => (
          <ellipse key={`${rx}-${i}`} cx="0" cy="0" rx={rx} ry={ry} transform={`rotate(${(180 / n) * i})`} />
        )))}
        <circle r="47" />
        <circle r="48.5" />
      </g>
    </svg>
  );
}

/** Small brand mark: a compact rosette in gold on the night sidebar, with the rupee sign at its centre. */
export function BrandMark() {
  return (
    <svg className="brand-mark" viewBox="-50 -50 100 100" aria-hidden="true" focusable="false">
      <circle r="48" fill="#13254a" stroke="#d4ad3f" strokeWidth="2" />
      <g fill="none" stroke="#d4ad3f" strokeOpacity="0.55" strokeWidth="1.1">
        {Array.from({ length: 12 }, (_, i) => <ellipse key={i} rx="38" ry="15" transform={`rotate(${15 * i})`} />)}
      </g>
      <circle r="17" fill="#13254a" stroke="#d4ad3f" strokeWidth="1.5" />
      <text x="0" y="8" textAnchor="middle" fontSize="22" fontWeight="700" fill="#f3dfa0" fontFamily="'Noto Sans', sans-serif">₹</text>
    </svg>
  );
}
