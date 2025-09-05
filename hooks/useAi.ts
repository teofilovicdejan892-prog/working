import AsyncStorage from "@react-native-async-storage/async-storage";
import { useMutation } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { AUTH_CONFIG } from "../config/auth";

const BASE_URL = process.env.P8_FS_API || "https://p8fs.percolationlabs.ai";
const API_URL = `${BASE_URL}/api/v1/chat/completions`;

type Role = "system" | "user" | "assistant";

export interface Message {
  role: Role;
  content: string;
}

export function useAi() {
  const [messages, setMessages] = useState<Message[]>([
    { role: "system", content: "You are a helpful assistant." },
  ]);
  const [isStreaming, setIsStreaming] = useState(false);
  const controllerRef = useRef<AbortController | null>(null);

  const getAuthToken = async (): Promise<string> => {
    const token = await AsyncStorage.getItem(
      AUTH_CONFIG.STORAGE_KEYS.ACCESS_TOKEN
    );
    if (!token) throw new Error("No access token found");
    return token;
  };

  const chatMutation = useMutation({
    mutationFn: async (userMessage: string) => {
      const newMessages = [
        ...messages,
        { role: "user" as const, content: userMessage },
      ];
      setMessages(newMessages);

      // ✅ keep system + last 2 exchanges (user+assistant) + new user message
      const contextMessages = [
        newMessages[0], // system
        ...newMessages.slice(-3), // last 3 (user/assistant)
      ];

      const token = await getAuthToken();
      console.log("Sending with context:", contextMessages);

      controllerRef.current = new AbortController();
      const response = await fetch(API_URL, {
        method: "POST",
        headers: {
          "X-P8-Agent": "p8-research",
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({
          model: "gpt-4.1-mini",
          messages: contextMessages,
          temperature: 0.7,
          max_tokens: 1000,
          stream: false,
        }),
        signal: controllerRef.current.signal,
      });

      const data = await response.json();
      if (!response.ok) {
        console.error("API error:", data);
        throw new Error(data?.error?.message || "API request failed");
      }

      let assistantMessage = data.choices?.[0]?.message?.content || "";
      setMessages((prev) => [
        ...prev,
        { role: "assistant", content: assistantMessage },
      ]);
    },
  });

  const stop = () => {
    controllerRef.current?.abort();
    setIsStreaming(false);
  };

  return {
    messages,
    isStreaming,
    sendMessage: (msg: string) => chatMutation.mutateAsync(msg),
    stop,
  };
}
