package com.clinicops.filestorage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Local disk + a Docker volume, not S3/cloud (decided 2026-09-20, phase 9
 * plan mode, ahead of the phase that would first need it - see CLAUDE.md's
 * own file-storage decision). {@code uploads-root} is mounted as a named
 * Docker volume in production (see docker-compose.yml's `uploads_data`) so
 * files survive a container recreate; a plain local directory otherwise
 * (`start-local.ps1`).
 *
 * One file per (subdirectory, ownerId) pair - a later {@link #store} call
 * for the same id overwrites, matching "current file only, no history" the
 * same way MedicalHistory/Vitals treat their own singleton-per-owner rows.
 * The stored filename is always {@code <ownerId><extension>}, never the
 * caller-supplied original filename - callers must derive {@code extension}
 * themselves from a validated content type (see ProviderController's own
 * upload endpoint), never trust a client-supplied filename directly, to
 * avoid path traversal or an unexpected extension reaching disk.
 */
@Service
public class FileStorageService {

    private final Path uploadsRoot;

    public FileStorageService(@Value("${clinicops.file-storage.uploads-root}") String uploadsRoot) {
        this.uploadsRoot = Path.of(uploadsRoot);
    }

    public String store(String subdirectory, UUID ownerId, String extension, InputStream content) {
        try {
            String filename = ownerId + extension;
            Path dir = uploadsRoot.resolve(subdirectory);
            Files.createDirectories(dir);
            Path target = dir.resolve(filename);
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
            return filename;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store uploaded file", e);
        }
    }

    public byte[] read(String subdirectory, String filename) {
        try {
            return Files.readAllBytes(uploadsRoot.resolve(subdirectory).resolve(filename));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read stored file", e);
        }
    }

    public void delete(String subdirectory, String filename) {
        try {
            Files.deleteIfExists(uploadsRoot.resolve(subdirectory).resolve(filename));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete stored file", e);
        }
    }
}
