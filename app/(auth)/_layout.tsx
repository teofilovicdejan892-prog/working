import { Stack } from "expo-router";

export default function AuthLayout() {
  return (
    <Stack screenOptions={{ headerShown: false }}>
      <Stack.Screen name="device-authentication" />
      <Stack.Screen name="verify-email" />
    </Stack>
  );
}
