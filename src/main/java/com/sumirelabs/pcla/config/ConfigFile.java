package com.sumirelabs.pcla.config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class ConfigFile {
    private ConfigFile() {}
    public static LightingConfig load(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            if (!Files.exists(file)) {
                try (InputStream defaults = ConfigFile.class.getResourceAsStream("/pcla-defaults.json")) {
                    if (defaults == null) throw new IOException("Missing bundled default configuration");
                    Files.copy(defaults, file);
                }
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { return ConfigCodec.read(reader); }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot load PCLA configuration " + file.toAbsolutePath() + ": " + e.getMessage()
                    + ". The existing file has not been replaced.", e);
        }
    }
}
