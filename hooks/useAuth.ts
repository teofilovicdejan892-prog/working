import AsyncStorage from "@react-native-async-storage/async-storage";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Platform } from "react-native";
import * as naclUtil from "tweetnacl-util";
import { AUTH_CONFIG } from "../config/auth";
import {
  AuthError,
  AuthState,
  TokenResponse,
  VerifyCodeParams,
} from "../types/auth";
import { generateKeyPair, KeyPair, signMessage } from "../utils/ed25519";

const useAuth = () => {
  const queryClient = useQueryClient();
  const [state, setState] = useState<AuthState>({
    isAuthenticated: false,
    accessToken: null,
  });

  useEffect(() => {
    initializeAuth();
  }, []);

  const initializeAuth = async () => {
    try {
      const [token, expiresAt, priv, pub] = await Promise.all([
        AsyncStorage.getItem(AUTH_CONFIG.STORAGE_KEYS.ACCESS_TOKEN),
        AsyncStorage.getItem(AUTH_CONFIG.STORAGE_KEYS.EXPIRES_AT),
        AsyncStorage.getItem(AUTH_CONFIG.STORAGE_KEYS.ED_PRIV_KEY),
        AsyncStorage.getItem(AUTH_CONFIG.STORAGE_KEYS.ED_PUB_KEY),
      ]);

      if (token && expiresAt && Number(expiresAt) > Date.now()) {
        setState({ isAuthenticated: true, accessToken: token });
      } else {
        await clearStoredTokens();
        setState({ isAuthenticated: false, accessToken: null });
      }

      if (!priv || !pub) {
        console.log("Generating new Ed25519 key pair");
        const kp = generateKeyPair();
        console.log("Generated key pair", kp);
        await Promise.all([
          AsyncStorage.setItem(
            AUTH_CONFIG.STORAGE_KEYS.ED_PRIV_KEY,
            kp.privateKeyBase64
          ),
          AsyncStorage.setItem(
            AUTH_CONFIG.STORAGE_KEYS.ED_PUB_KEY,
            kp.publicKeyBase64
          ),
        ]);
      }
    } catch (error) {
      console.error("Auth initialization failed:", error);
      throw new AuthError("Failed to initialize authentication");
    }
  };

  const saveTokens = async (tokenResponse: TokenResponse): Promise<void> => {
    const now = Date.now();
    const expiresAt = now + tokenResponse.expires_in * 1000;

    try {
      const storageOperations = [
        AsyncStorage.setItem(
          AUTH_CONFIG.STORAGE_KEYS.ACCESS_TOKEN,
          tokenResponse.access_token
        ),
        AsyncStorage.setItem(
          AUTH_CONFIG.STORAGE_KEYS.EXPIRES_AT,
          String(expiresAt)
        ),
      ];

      if (tokenResponse.refresh_token) {
        storageOperations.push(
          AsyncStorage.setItem(
            AUTH_CONFIG.STORAGE_KEYS.REFRESH_TOKEN,
            tokenResponse.refresh_token
          )
        );
      } else {
        storageOperations.push(
          AsyncStorage.removeItem(AUTH_CONFIG.STORAGE_KEYS.REFRESH_TOKEN)
        );
      }

      await Promise.all(storageOperations);

      setState({
        isAuthenticated: true,
        accessToken: tokenResponse.access_token,
      });
      queryClient.invalidateQueries();
    } catch (error) {
      throw new AuthError("Failed to save authentication tokens");
    }
  };

  const clearStoredTokens = async (): Promise<void> => {
    try {
      await Promise.all([
        AsyncStorage.removeItem(AUTH_CONFIG.STORAGE_KEYS.ACCESS_TOKEN),
        AsyncStorage.removeItem(AUTH_CONFIG.STORAGE_KEYS.REFRESH_TOKEN),
        AsyncStorage.removeItem(AUTH_CONFIG.STORAGE_KEYS.EXPIRES_AT),
      ]);
    } catch (error) {
      console.error("Failed to clear tokens:", error);
    }
  };

  const loadOrCreateKeyPair = async (): Promise<KeyPair> => {
    try {
      const [privBase64, pubBase64] = await Promise.all([
        AsyncStorage.getItem(AUTH_CONFIG.STORAGE_KEYS.ED_PRIV_KEY),
        AsyncStorage.getItem(AUTH_CONFIG.STORAGE_KEYS.ED_PUB_KEY),
      ]);

      if (privBase64 && pubBase64) {
        return {
          privateKey: naclUtil.decodeBase64(privBase64),
          publicKey: naclUtil.decodeBase64(pubBase64),
          privateKeyBase64: privBase64,
          publicKeyBase64: pubBase64,
        };
      }

      const keyPair = generateKeyPair();
      await Promise.all([
        AsyncStorage.setItem(
          AUTH_CONFIG.STORAGE_KEYS.ED_PRIV_KEY,
          keyPair.privateKeyBase64
        ),
        AsyncStorage.setItem(
          AUTH_CONFIG.STORAGE_KEYS.ED_PUB_KEY,
          keyPair.publicKeyBase64
        ),
      ]);
      return keyPair;
    } catch (error) {
      console.error("Key pair load/create failed:", error);
      throw new AuthError("Failed to manage keypair");
    }
  };

  const sendCodeMutation = useMutation({
    mutationFn: async (email: string) => {
      if (!email?.includes("@")) {
        throw new AuthError("Invalid email format");
      }

      const keyPair = await loadOrCreateKeyPair();
      const body = {
        email,
        public_key: keyPair.publicKeyBase64,
        device_info: {
          platform: Platform?.OS ?? "unknown",
        },
      };

      const response = await fetch(
        `${AUTH_CONFIG.BASE_URL}/api/v1/auth/register`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        }
      );

      if (!response.ok) {
        const errorText = await response.text();
        throw new AuthError(
          `Registration failed: ${response.status} ${errorText}`
        );
      }

      return response.json();
    },
  });

  const verifyCodeMutation = useMutation<
    TokenResponse,
    Error,
    VerifyCodeParams
  >({
    mutationFn: async (params) => {
      const keyPair = await loadOrCreateKeyPair();
      const signature = signMessage(params.challenge, keyPair.privateKey);

      const response = await fetch(
        `${AUTH_CONFIG.BASE_URL}/api/v1/auth/verify`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            ...params,
            signature,
          }),
        }
      );

      if (!response.ok) {
        const errorText = await response.text();
        throw new AuthError(
          `Verification failed: ${response.status} ${errorText}`
        );
      }

      const tokenResponse: TokenResponse = await response.json();
      console.log("Received token response", tokenResponse);
      await saveTokens(tokenResponse);
      return tokenResponse;
    },
  });

  const getJwt = async (): Promise<string | null> => {
    try {
      const [expiresAt, token] = await Promise.all([
        AsyncStorage.getItem(AUTH_CONFIG.STORAGE_KEYS.EXPIRES_AT),
        AsyncStorage.getItem(AUTH_CONFIG.STORAGE_KEYS.ACCESS_TOKEN),
      ]);

      if (!token || !expiresAt || Number(expiresAt) <= Date.now()) {
        await clearStoredTokens();
        setState({ isAuthenticated: false, accessToken: null });
        return null;
      }

      return token;
    } catch (error) {
      console.error("JWT retrieval failed:", error);
      return null;
    }
  };

  const signOut = async (): Promise<void> => {
    try {
      const refreshToken = await AsyncStorage.getItem(
        AUTH_CONFIG.STORAGE_KEYS.REFRESH_TOKEN
      );

      if (refreshToken) {
        try {
          const body = new URLSearchParams({ token: refreshToken });
          await fetch(`${AUTH_CONFIG.BASE_URL}/oauth/revoke`, {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: body.toString(),
          });
        } catch (error) {
          console.warn("Token revocation failed:", error);
        }
      }
    } finally {
      await clearStoredTokens();
      setState({ isAuthenticated: false, accessToken: null });
      queryClient.clear();
    }
  };

  return {
    isAuthenticated: state.isAuthenticated,
    sendCode: (email: string) => sendCodeMutation.mutateAsync(email),
    verifyCode: (params: VerifyCodeParams) =>
      verifyCodeMutation.mutateAsync(params),
    getJwt,
    signOut,
    sendCodeState: {
      isLoading: sendCodeMutation.isPending,
      error: sendCodeMutation.error,
      data: sendCodeMutation.data,
    },
    verifyCodeState: {
      isLoading: verifyCodeMutation.isPending,
      error: verifyCodeMutation.error,
      data: verifyCodeMutation.data,
    },
  };
};

export default useAuth;
