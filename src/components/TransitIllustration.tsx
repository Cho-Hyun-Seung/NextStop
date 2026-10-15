export function TransitIllustration() {
  return (
    <svg className="transit-illustration" viewBox="0 0 380 244" fill="none" aria-hidden="true">
      <circle cx="245" cy="122" r="105" fill="var(--primary-soft)" />
      <path d="M23 188H298C330 188 350 168 350 138V68" stroke="var(--primary)" strokeWidth="3" strokeLinecap="round" />
      <path d="M50 65H151C177 65 190 78 190 104V201" stroke="var(--primary)" strokeWidth="2" strokeDasharray="4 7" strokeLinecap="round" opacity=".28" />
      <circle cx="55" cy="188" r="6" fill="var(--surface)" stroke="var(--primary)" strokeWidth="3" />
      <circle cx="117" cy="188" r="6" fill="var(--surface)" stroke="var(--primary)" strokeWidth="3" />
      <circle cx="293" cy="188" r="6" fill="var(--surface)" stroke="var(--primary)" strokeWidth="3" />
      <circle cx="350" cy="76" r="6" fill="var(--surface)" stroke="var(--primary)" strokeWidth="3" />
      <rect x="152" y="78" width="99" height="93" rx="24" fill="var(--primary)" />
      <rect x="166" y="92" width="71" height="38" rx="10" fill="var(--background)" />
      <path d="M201 94V129" stroke="var(--primary)" strokeWidth="3" />
      <rect x="184" y="139" width="34" height="5" rx="2.5" fill="var(--on-primary)" opacity=".5" />
      <circle cx="173" cy="146" r="5" fill="var(--background)" /><circle cx="230" cy="146" r="5" fill="var(--background)" />
      <path d="M176 170L164 184M226 170L238 184" stroke="var(--primary)" strokeWidth="6" strokeLinecap="round" />
      <rect x="253" y="36" width="101" height="33" rx="16.5" fill="var(--surface)" stroke="var(--primary)" strokeWidth="1.5" />
      <circle cx="269" cy="52" r="4" fill="var(--primary)" />
      <text x="280" y="56" fill="var(--primary-strong)" fontSize="10" fontFamily="sans-serif" fontWeight="700" letterSpacing=".8">NEXT STOP</text>
      <path d="M108 105H131M120 94V117" stroke="var(--primary)" strokeWidth="2" opacity=".4" strokeLinecap="round" />
      <circle cx="303" cy="110" r="3" fill="var(--primary)" opacity=".5" />
    </svg>
  )
}
