import { Stack } from "expo-router";

export default function AppLayout() {
  return (
    <Stack screenOptions={{ headerShown: false }}>
      <Stack.Screen name="success" />
      <Stack.Screen name="(tabs)" />
    </Stack>
  );
}
