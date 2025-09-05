import { Message } from "@/hooks/useAi";
import React from "react";
import { FlatList, StyleSheet, Text, View } from "react-native";

interface Props {
  messages: Message[];
}

export default function ChatMessages({ messages }: Props) {
  return (
    <FlatList
      data={[...messages].reverse().filter((m) => m.role !== "system")} // reversed for inverted list
      keyExtractor={(_, index) => index.toString()}
      renderItem={({ item }) => (
        <View
          style={[
            styles.messageContainer,
            item.role === "user" ? styles.userMessage : styles.assistantMessage,
          ]}
        >
          <Text
            style={[
              styles.messageText,
              item.role === "user"
                ? styles.userMessageText
                : styles.assistantMessageText,
            ]}
          >
            {item.content}
          </Text>
        </View>
      )}
      inverted
      ListFooterComponent={null}
      contentContainerStyle={styles.list}
    />
  );
}

const styles = StyleSheet.create({
  list: {
    paddingBottom: 60,
    paddingVertical: 10,
    paddingHorizontal: 12,
  },
  messageContainer: {
    maxWidth: "90%",
    marginVertical: 6,
    padding: 10,
    borderRadius: 12,
    margin: 20,
  },
  userMessage: {
    alignSelf: "flex-end",
    backgroundColor: "#0A84FF",
    borderBottomRightRadius: 4,
  },
  assistantMessage: {
    alignSelf: "flex-start",
    backgroundColor: "#2C2C2E",
    borderBottomLeftRadius: 4,
  },
  messageText: {
    fontSize: 16,
    lineHeight: 20,
  },
  userMessageText: {
    color: "#fff",
  },
  assistantMessageText: {
    color: "#fff",
  },
  typingIndicator: {
    flexDirection: "row",
    alignItems: "center",
    marginVertical: 8,
    marginLeft: 6,
  },
  typingText: {
    marginLeft: 6,
    color: "#999",
    fontSize: 14,
  },
});
