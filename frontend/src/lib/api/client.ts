import { API_BASE_URL } from "../env";

/**
 * A normalized, safe API error.
 *
 * - `status` preserves the HTTP status (0 for a network/transport failure) so
 *   callers can branch on 401, 409, etc.
 * - `message` is always safe to display: it is either a controlled fallback for
 *   the status or a server `message` the backend is known to return for the
 *   auth surface. It never contains a stack trace, SQL, or credentials.
 * - `detail` carries the raw server-provided `message` when one was present
 *   (already vetted safe), otherwise undefined.
 *
 * This client never logs request or response bodies, tokens, or credentials.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly detail: string | undefined;

  constructor(status: number, message: string, detail?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.detail = detail;
  }
}

/** Options for a single request. */
export interface RequestOptions {
  method?: string;
  /** JSON-serializable body. When present, Content-Type: application/json is set. */
  body?: unknown;
  /** Bearer token. When present and non-empty, Authorization is set. */
  token?: string | null;
  signal?: AbortSignal;
}

/**
 * Builds a request URL from the configured API base and a base-relative path.
 *
 * Callers pass paths WITHOUT the base prefix (e.g. "/auth/login"); the base
 * (e.g. "/api") is added exactly once here, so a "/api" prefix can never be
 * duplicated. A leading slash is normalized.
 */
export function buildUrl(path: string): string {
  const base = API_BASE_URL.replace(/\/+$/, "");
  const suffix = path.startsWith("/") ? path : `/${path}`;
  return `${base}${suffix}`;
}

/** A safe, user-facing fallback message for a status with no server message. */
function fallbackMessage(status: number): string {
  if (status === 400) return "The request could not be completed. Please check your input.";
  if (status === 401) return "Your session is invalid. Please sign in again.";
  if (status === 403) return "You do not have permission to perform this action.";
  if (status === 404) return "The requested item could not be found.";
  if (status === 409) return "That action conflicts with existing data.";
  if (status === 413) return "That file is too large to upload.";
  if (status === 422) return "The submitted data could not be processed.";
  if (status === 429) return "Too many requests. Please wait and try again.";
  if (status >= 500) return "The server encountered an error. Please try again.";
  return "Something went wrong. Please try again.";
}

/**
 * Extracts a safe displayable message from an error body.
 *
 * The backend's auth errors (and dataset/policy/run errors) return a JSON
 * object of the shape `{ "message": "..." }` with deliberately safe, generic
 * text. Only that field is surfaced. Any other shape (e.g. Spring's default
 * validation `ProblemDetail`) is ignored in favor of a controlled fallback, so
 * implementation details can never leak to the UI.
 */
function extractSafeMessage(data: unknown): string | undefined {
  if (typeof data === "object" && data !== null && "message" in data) {
    const value = (data as { message?: unknown }).message;
    if (typeof value === "string" && value.trim().length > 0) {
      return value;
    }
  }
  return undefined;
}

/** Reads a response body as JSON, returning undefined for empty/invalid JSON. */
async function readJson(response: Response): Promise<unknown> {
  const text = await response.text();
  if (text.length === 0) return undefined;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return undefined;
  }
}

/**
 * Performs a typed JSON request and returns the parsed body.
 *
 * Behavior:
 *  - Sets `Content-Type: application/json` and serializes `body` only when a
 *    body is provided.
 *  - Sets `Authorization: Bearer <token>` only when a non-empty token is given.
 *  - Returns `undefined` for HTTP 204 (no content).
 *  - Normalizes any non-2xx response into an `ApiError`, preserving status and
 *    surfacing only a safe message.
 *  - Handles empty/invalid JSON on a 2xx without leaking details.
 *  - Supports an `AbortSignal`; an aborted request re-throws the AbortError.
 *  - Wraps transport failures (offline, DNS, etc.) as `ApiError` with status 0.
 *
 * Extension points for later phases (not implemented yet): raw `text/csv`
 * uploads and `Blob` artifact downloads will add dedicated helpers rather than
 * overloading this JSON-focused one.
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, token, signal } = options;

  const headers: Record<string, string> = { Accept: "application/json" };
  let payload: string | undefined;
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    payload = JSON.stringify(body);
  }
  if (token !== null && token !== undefined && token.length > 0) {
    headers.Authorization = `Bearer ${token}`;
  }

  let response: Response;
  try {
    response = await fetch(buildUrl(path), {
      method,
      headers,
      body: payload,
      signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw error;
    }
    throw new ApiError(
      0,
      "Unable to reach the server. Please check your connection and try again.",
    );
  }

  // No-content responses carry no body.
  if (response.status === 204) {
    return undefined as T;
  }

  const data = await readJson(response);

  if (!response.ok) {
    const detail = extractSafeMessage(data);
    throw new ApiError(response.status, detail ?? fallbackMessage(response.status), detail);
  }

  // A 2xx that should have carried JSON but did not is treated as an error
  // rather than silently returning undefined typed as T.
  if (data === undefined) {
    throw new ApiError(
      response.status,
      "Received an unexpected empty response from the server.",
    );
  }

  return data as T;
}

