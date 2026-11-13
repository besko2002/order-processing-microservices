/** Formatting helpers. Each tolerates null so no screen prints "null" or "NaN". */

/** Money with two decimals and thousands separators, followed by the ISO currency code. */
export function formatMoney(value: number | null | undefined, currency = 'EUR'): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '—';
  }
  const amount = value.toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  return `${amount} ${currency}`;
}

/** An ISO instant → `2026-11-08 18:22:37 UTC`, so the value can be checked by eye. */
export function formatDateTime(value: string | null | undefined): string {
  if (value === null || value === undefined) {
    return '—';
  }
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }
  return `${parsed.toISOString().slice(0, 19).replace('T', ' ')} UTC`;
}

/** Turns an enum such as `CANCELLATION_PENDING` into `Cancellation pending`. */
export function humanizeEnum(value: string): string {
  const words = value.toLowerCase().replace(/_/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}
