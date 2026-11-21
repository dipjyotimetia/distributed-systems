// Axios leaves date-time values as strings, and the app sends enum values as their
// wire strings. The generated models use Date and TypeScript enums. This view keeps
// the generated field set and uses the JSON form the dev client actually exchanges.
type WireValue<T> = [NonNullable<T>] extends [Date]
  ? string | Exclude<T, Date>
  : [NonNullable<T>] extends [string]
    ? `${NonNullable<T>}` | Exclude<T, string>
    : T;

export type Wire<T> = { [K in keyof T]: WireValue<T[K]> };
