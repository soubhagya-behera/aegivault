/**
 * Frontend environment access.
 *
 * The API base URL comes from VITE_API_BASE_URL and defaults to "/api" (the
 * documented local default). Only VITE_-prefixed variables reach client code,
 * so no server-side config or secrets are exposed here.
 */
export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL || "/api";
