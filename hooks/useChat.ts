import * as FileSystem from "expo-file-system";
import * as ImagePicker from "expo-image-picker";
import { useState } from "react";
import { Alert } from "react-native";

const useChat = () => {
  const [message, setMessage] = useState("");
  const [mediaUri, setMediaUri] = useState<string | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);
  const [fileSize, setFileSize] = useState<number | null>(null);

  // Single image picker function — only images
  const pickImage = async () => {
    try {
      const result = await ImagePicker.launchImageLibraryAsync({
        mediaTypes: ImagePicker.MediaTypeOptions.Images,
        allowsEditing: true,
        aspect: [4, 3],
        quality: 1,
      });

      if (!result.canceled && result.assets && result.assets.length > 0) {
        const uri = result.assets[0].uri;
        setMediaUri(uri);
        setFileName(uri.split("/").pop() || null);
        try {
          const info = await FileSystem.getInfoAsync(uri);
          setFileSize(info.exists ? info.size : null);
        } catch (e) {
          // ignore
        }
      }
    } catch (err) {
      console.warn("pickImage error", err);
      Alert.alert("Error", "Could not pick the image.");
    }
  };

  const resetInput = () => {
    if (message.trim() || mediaUri) {
      setMessage("");
      setMediaUri(null);
      setFileName(null);
      setFileSize(null);
    }
  };

  return {
    message,
    setMessage,
    mediaUri,
    fileName,
    fileSize,
    pickImage,
    resetInput,
  };
};

export default useChat;
