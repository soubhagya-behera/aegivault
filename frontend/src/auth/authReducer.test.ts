import { describe, expect, it } from "vitest";
import { authReducer, initialAuthState } from "./authReducer";
import type { UserProfile } from "../types/auth";

const user: UserProfile = {
  userId: "u1",
  email: "analyst@example.com",
  displayName: null,
  roles: ["USER"],
};

describe("authReducer", () => {
  it("starts unauthenticated with no user", () => {
    expect(initialAuthState).toEqual({ user: null, status: "unauthenticated" });
  });

  it("marks the session authenticated and stores the user", () => {
    const next = authReducer(initialAuthState, { type: "authenticated", user });
    expect(next).toEqual({ user, status: "authenticated" });
  });

  it("clears user and status on logout", () => {
    const authed = authReducer(initialAuthState, { type: "authenticated", user });
    expect(authReducer(authed, { type: "loggedOut" })).toEqual(initialAuthState);
  });
});
