import { formatWithOptions } from "node:util";

// Loaded before Next.js: its proxy and request-error logs bypass app handlers.
// Drop the whole capability URL suffix, including query strings and fragments.
const qrUrl = /(\/(?:api\/v1\/public\/meal-usage-qr|qr)\/)[^\s"'<>]+/gi;

for (const method of ["log", "info", "warn", "error", "debug"]) {
  const write = console[method].bind(console);
  console[method] = (...args) => {
    let message;
    try {
      message = formatWithOptions({ customInspect: false, colors: false }, ...args);
    } catch {
      write("operational_event=log_format_failed");
      return;
    }
    write(message.replace(qrUrl, "$1[REDACTED]"));
  };
}
