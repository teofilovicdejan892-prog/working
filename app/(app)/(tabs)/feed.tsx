import { StyleSheet, Text, View } from "react-native";

export default function Feed() {
  return (
    <View style={styles.container}>
      <Text style={styles.title}>Feed</Text>
      {/* Add feed content here */}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#000",
    alignItems: "center",
    justifyContent: "center",
  },
  title: {
    fontSize: 24,
    color: "#FFF",
  },
});
