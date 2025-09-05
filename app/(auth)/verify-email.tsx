import useAuth from "@/hooks/useAuth";
import { useRouter } from "expo-router";
import { useLocalSearchParams } from "expo-router/build/hooks";
import React, { useEffect, useRef, useState } from "react";
import {
  ActivityIndicator,
  Alert,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from "react-native";

export default function VerifyEmail() {
  const router = useRouter();
  const params = useLocalSearchParams();
  const email = (params?.email as string) ?? "";
  const initialChallenge = (params?.challenge as string) ?? "";

  const { verifyCode, verifyCodeState, sendCode } = useAuth();

  // OTP digits as array of strings
  const [digits, setDigits] = useState<string[]>(["", "", "", "", "", ""]);
  const inputsRef = useRef<(TextInput | null)[]>([]);
  const [challenge, setChallenge] = useState<string>(initialChallenge);
  const [error, setError] = useState<string | null>(null);

  // focus first input on mount
  useEffect(() => {
    setTimeout(() => inputsRef.current[0]?.focus(), 100);
  }, []);

  const setDigit = (index: number, value: string) => {
    // allow only digits
    const v = value.replace(/\D/g, "");
    // if pasted full code (length > 1) fill all
    if (v.length > 1) {
      const chars = v.slice(0, 6).split("");
      const newDigits = ["", "", "", "", "", ""];
      for (let i = 0; i < chars.length; i++) newDigits[i] = chars[i];
      setDigits(newDigits);
      // focus next empty
      const firstEmpty = newDigits.findIndex((d) => d === "");
      if (firstEmpty >= 0) {
        inputsRef.current[firstEmpty]?.focus();
      } else {
        inputsRef.current[5]?.blur();
      }
      return;
    }

    setDigits((prev) => {
      const next = [...prev];
      next[index] = v;
      return next;
    });

    if (v && index < 5) {
      // focus next
      inputsRef.current[index + 1]?.focus();
    }
  };

  const handleKeyPress = (
    e: { nativeEvent: { key: string } },
    index: number
  ) => {
    if (e.nativeEvent.key === "Backspace") {
      if (!digits[index] && index > 0) {
        inputsRef.current[index - 1]?.focus();
      }
    }
  };

  const submitCode = async () => {
    setError(null);
    const code = digits.join("");
    if (code.length !== 6) {
      setError("Please enter the 6-digit code.");
      return;
    }
    if (!email) {
      setError("Email is missing — go back and enter your email.");
      return;
    }

    try {
      await verifyCode({ email, code, challenge });
      // success — navigate to success screen (replace so user can't go back)
      router.replace("/success");
    } catch (err: any) {
      console.error("verify failed", err);
      const msg =
        err?.message ??
        (err?.toString && err.toString()) ??
        "Verification failed. Please try again.";
      setError(msg);
      Alert.alert("Verification failed", msg);
    }
  };

  const handleResend = async () => {
    setError(null);
    if (!email) {
      setError("Email is missing — go back and enter your email.");
      return;
    }
    try {
      const res = await sendCode(email);
      // try to extract challenge returned by server
      const newChallenge =
        res?.challenge ?? res?.data?.challenge ?? res?.challenge_base64 ?? "";
      if (newChallenge) setChallenge(newChallenge);
      Alert.alert("Sent", "Verification code resent. Check your inbox.");
    } catch (err: any) {
      console.error("resend failed", err);
      const msg = err?.message ?? "Failed to resend code.";
      setError(msg);
      Alert.alert("Resend failed", msg);
    }
  };

  const isLoading = !!verifyCodeState?.isLoading;

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Verify Your Email</Text>
      <Text style={styles.subtitle}>
        {`We've sent a 6-digit code to your email`}
      </Text>

      <View style={styles.emailRow}>
        <Text style={styles.emailText}>
          {email ? email : "No email provided"}
        </Text>
      </View>

      <View style={styles.codeContainer}>
        {[0, 1, 2, 3, 4, 5].map((i) => (
          <TextInput
            key={i}
            ref={(ref: TextInput | null) => {
              inputsRef.current[i] = ref;
            }}
            style={styles.codeInput}
            maxLength={1}
            keyboardType="number-pad"
            placeholderTextColor="#BBB"
            value={digits[i]}
            onChangeText={(text) => setDigit(i, text)}
            onKeyPress={(e) => handleKeyPress(e as any, i)}
            editable={!isLoading}
            returnKeyType={i === 5 ? "done" : "next"}
            onSubmitEditing={() => {
              if (i === 5) submitCode();
            }}
          />
        ))}
      </View>

      {error ? <Text style={styles.errorText}>{error}</Text> : null}

      <TouchableOpacity
        onPress={submitCode}
        style={[styles.button, isLoading ? styles.buttonDisabled : null]}
        disabled={isLoading}
      >
        {isLoading ? (
          <ActivityIndicator color="#fff" />
        ) : (
          <Text style={styles.buttonText}>Continue</Text>
        )}
      </TouchableOpacity>

      <View style={styles.row}>
        <TouchableOpacity onPress={handleResend} style={styles.resendButton}>
          <Text style={styles.resendText}>{"Resend code"}</Text>
        </TouchableOpacity>

        <TouchableOpacity
          onPress={() => router.push("/device-authentication")}
          style={styles.changeLink}
        >
          <Text style={styles.changeText}>Change Email</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#000",
    alignItems: "center",
    justifyContent: "center",
    padding: 20,
  },
  title: {
    fontSize: 24,
    color: "#FFF",
    marginBottom: 6,
  },
  subtitle: {
    fontSize: 16,
    color: "#BBB",
    textAlign: "center",
    marginBottom: 16,
  },
  emailRow: {
    marginBottom: 12,
  },
  emailText: {
    color: "#CCC",
  },
  codeContainer: {
    flexDirection: "row",
    marginBottom: 12,
  },
  codeInput: {
    width: 48,
    height: 48,
    borderColor: "#333",
    borderWidth: 1,
    borderRadius: 6,
    marginHorizontal: 6,
    textAlign: "center",
    color: "#FFF",
    backgroundColor: "#222",
    fontSize: 18,
  },
  errorText: {
    marginTop: 6,
    color: "#ff6b6b",
  },
  button: {
    backgroundColor: "#1E90FF",
    paddingVertical: 12,
    paddingHorizontal: 36,
    borderRadius: 6,
    marginTop: 12,
  },
  buttonDisabled: {
    opacity: 0.7,
  },
  buttonText: {
    color: "#FFF",
    textAlign: "center",
    fontWeight: "600",
  },
  row: {
    marginTop: 12,
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
  },
  resendButton: {
    paddingHorizontal: 8,
    paddingVertical: 6,
  },
  resendText: {
    color: "#BBB",
  },
  changeLink: {
    paddingHorizontal: 8,
    paddingVertical: 6,
  },
  changeText: {
    color: "#1E90FF",
  },
});
