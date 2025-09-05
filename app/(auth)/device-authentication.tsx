import useAuth from "@/hooks/useAuth";
import { Ionicons } from "@expo/vector-icons";
import { useRouter } from "expo-router";
import React, { useState } from "react";
import {
  ActivityIndicator,
  Alert,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from "react-native";

export default function DeviceAuthentication() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [localError, setLocalError] = useState<string | null>(null);

  const { sendCode, sendCodeState } = useAuth();

  const handleContinue = async () => {
    setLocalError(null);

    // basic client-side validation
    if (!email || !email.includes("@")) {
      setLocalError("Please enter a valid email address.");
      return;
    }

    try {
      const response = await sendCode(email);
      console.log("sendCode response", response);
      const challenge = response?.challenge ?? response?.registration_id ?? "";
      // Navigate to verify screen and pass email + challenge (challenge may be empty)
      router.push({
        pathname: "/verify-email",
        params: { email, challenge },
      });
    } catch (err: any) {
      console.error("sendCode failed", err);
      const message =
        err?.message ??
        err?.toString() ??
        "Failed to send verification code. Please try again.";
      // show friendly alert + inline error
      Alert.alert("Registration failed", message);
      setLocalError(message);
    }
  };

  const isLoading = !!sendCodeState?.isLoading;

  return (
    <View style={styles.container}>
      <Ionicons
        name="shield-checkmark-outline"
        size={50}
        color="#1E90FF"
        style={styles.icon}
      />
      <Text style={styles.title}>Device Authentication</Text>
      <Text style={styles.subtitle}>
        Enter your email address to register this device
      </Text>

      <TextInput
        style={styles.input}
        placeholder="Email Address"
        placeholderTextColor="#BBB"
        keyboardType="email-address"
        autoCapitalize="none"
        autoCorrect={false}
        value={email}
        onChangeText={(t) => setEmail(t)}
        editable={!isLoading}
      />

      {localError ? <Text style={styles.errorText}>{localError}</Text> : null}

      <TouchableOpacity
        onPress={handleContinue}
        style={[styles.button, isLoading ? styles.buttonDisabled : null]}
        disabled={isLoading}
      >
        {isLoading ? (
          <ActivityIndicator />
        ) : (
          <Text style={styles.buttonText}>Continue</Text>
        )}
      </TouchableOpacity>

      <Text style={styles.hint}>
        You will receive a code to verify your email. Check your inbox (and
        spam) after continuing.
      </Text>
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
  icon: {
    marginBottom: 20,
  },
  title: {
    fontSize: 24,
    color: "#FFF",
    marginBottom: 10,
  },
  subtitle: {
    fontSize: 16,
    color: "#BBB",
    textAlign: "center",
    marginBottom: 20,
  },
  input: {
    width: "80%",
    height: 44,
    borderColor: "#333",
    borderWidth: 1,
    borderRadius: 6,
    marginBottom: 10,
    paddingHorizontal: 12,
    color: "#FFF",
    backgroundColor: "#222",
  },
  errorText: {
    color: "#ff6b6b",
    marginBottom: 8,
  },
  button: {
    backgroundColor: "#0A84FF",
    paddingVertical: 12,
    paddingHorizontal: 40,
    borderRadius: 6,
    marginTop: 6,
  },
  buttonDisabled: {
    opacity: 0.6,
  },
  buttonText: {
    color: "#FFF",
    textAlign: "center",
    fontWeight: "600",
  },
  hint: {
    color: "#888",
    fontSize: 12,
    marginTop: 12,
    width: "80%",
    textAlign: "center",
  },
});
