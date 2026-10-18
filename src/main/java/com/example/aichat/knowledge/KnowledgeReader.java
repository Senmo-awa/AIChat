package com.example.aichat.knowledge;

import com.example.aichat.config.ConfigManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public final class KnowledgeReader {
    private KnowledgeReader() {
    }

    public static List<String> listFiles() {
        Path dir = ConfigManager.dir().resolve(ConfigManager.get().knowledge_dir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static String read(String fileName) throws IOException {
        List<String> allowed = listFiles();
        if (!allowed.contains(fileName)) {
            throw new IOException("不允许的知识库文件: " + fileName);
        }
        Path base = ConfigManager.dir()
                .resolve(ConfigManager.get().knowledge_dir)
                .normalize();
        Path file = base.resolve(fileName).normalize();
        if (!file.startsWith(base)) {
            throw new IOException("路径越界: " + fileName);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
