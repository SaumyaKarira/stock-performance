package com.project.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Thin helpers for common path / file operations.
 *
 * <p>Design decision: utility methods are package-private where they are only
 * needed by one layer, but public here because both service and main entry-point
 * reference them.</p>
 */
public final class FileUtils {

    private FileUtils() { /* no instances */ }

    /**
     * Resolves a path string relative to the current working directory if it is
     * not already absolute.
     */
    public static Path resolve(String pathStr) {
        Path p = Paths.get(pathStr);
        return p.isAbsolute() ? p : Paths.get("").toAbsolutePath().resolve(p);
    }

    /**
     * Ensures the parent directories of the given target path exist,
     * creating them if necessary.
     *
     * @throws UncheckedIOException if directory creation fails
     */
    public static void ensureParentExists(Path target) {
        Path parent = target.getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create directory: " + parent, e);
            }
        }
    }

    /**
     * Returns a sanitised filename segment from a raw string
     * (strips characters that are illegal on most OS file systems).
     */
    public static String sanitiseFileName(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}

