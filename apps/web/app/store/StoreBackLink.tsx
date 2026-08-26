import Link from "next/link";

const baseClassName = "inline-flex min-h-10 items-center text-sm font-semibold text-[#244cda] underline decoration-[#b9c8ff] underline-offset-4 hover:text-[var(--accent)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus)] focus-visible:ring-offset-2";

export function StoreBackLink({ href, className = "" }: Readonly<{ href: string; className?: string }>) {
  return <Link className={`${baseClassName} ${className}`.trim()} href={href}>← 뒤로가기</Link>;
}
