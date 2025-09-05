import { Link } from "expo-router";
import { StyleSheet, Text, View } from "react-native";

export default function Success() {
  return (
    <View style={styles.container}>
      <Text style={styles.title}>Eepis Assistant</Text>
      <Text style={styles.subtitle}>Successfully verified!</Text>
      <Link href="/(app)/(tabs)/chat" style={styles.button}>
        <Text style={styles.buttonText}>Go to App</Text>
      </Link>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#4A90E2", // Softer blue color
    alignItems: "center",
    justifyContent: "center",
    padding: 24,
  },
  title: {
    fontSize: 32,
    fontWeight: "bold",
    color: "#FFF",
    marginBottom: 16,
  },
  subtitle: {
    fontSize: 18,
    color: "rgba(255, 255, 255, 0.9)",
    textAlign: "center",
    marginBottom: 32,
  },
  button: {
    backgroundColor: "#FFF",
    paddingVertical: 12,
    paddingHorizontal: 24,
    borderRadius: 8,
    elevation: 2,
    shadowColor: "#000",
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
  },
  buttonText: {
    color: "#4A90E2",
    fontSize: 16,
    fontWeight: "600",
    textAlign: "center",
  },
});
