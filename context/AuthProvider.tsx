import useAuth from "@/hooks/useAuth";
import React, { createContext, ReactNode, useContext } from "react";
import { VerifyCodeParams } from "../types/auth";

type AuthContextType = {
  isAuthenticated: boolean;
  sendCode: (email: string) => Promise<any>;
  verifyCode: (params: VerifyCodeParams) => Promise<any>;
  getJwt: () => Promise<string | null>;
  signOut: () => Promise<void>;
  sendCodeState: {
    isLoading: boolean;
    error: unknown;
    data: any;
  };
  verifyCodeState: {
    isLoading: boolean;
    error: unknown;
    data: any;
  };
};

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const auth = useAuth();

  return <AuthContext.Provider value={auth}>{children}</AuthContext.Provider>;
}

export function useAuthContext() {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuthContext must be used within AuthProvider");
  }
  return ctx;
}
