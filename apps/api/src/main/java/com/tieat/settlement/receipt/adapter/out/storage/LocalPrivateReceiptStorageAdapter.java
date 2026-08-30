package com.tieat.settlement.receipt.adapter.out.storage;

import com.tieat.settlement.receipt.domain.ReceiptStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Development-only private storage; the root is never served by the web application. */
@Component
@ConditionalOnProperty(name = "tieat.receipts.provider", havingValue = "local")
public final class LocalPrivateReceiptStorageAdapter implements ReceiptStorage {

    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    );

    private final Path root;

    @Autowired
    public LocalPrivateReceiptStorageAdapter(
        @Value("${tieat.receipts.local-root}") String root
    ) {
        this(Path.of(root));
    }

    public LocalPrivateReceiptStorageAdapter(Path root) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
    }

    @Override
    public void put(String objectKey, byte[] bytes, String contentType) {
        validatePayload(objectKey, bytes, contentType);
        Path path = path(objectKey);
        try {
            Files.createDirectories(path.getParent());
            makePrivate(path.getParent());
            Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            makePrivate(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Private receipt write failed", exception);
        }
    }

    @Override
    public byte[] get(String objectKey) {
        validateKey(objectKey);
        try {
            return Files.readAllBytes(path(objectKey));
        } catch (IOException exception) {
            throw new IllegalStateException("Private receipt read failed", exception);
        }
    }

    @Override
    public void deleteAllVersions(String objectKey) {
        validateKey(objectKey);
        Path target = path(objectKey);
        try {
            Files.deleteIfExists(target);
            // Recovery copies are kept next to the opaque object key by any local backup process.
            Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".backup"));
            Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".version"));
        } catch (IOException exception) {
            throw new IllegalStateException("Private receipt deletion failed", exception);
        }
    }

    @Override
    public void reconcileOrphans(Set<String> knownObjectKeys) {
        Objects.requireNonNull(knownObjectKeys, "Known receipt object keys must be supplied");
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                .map(root::relativize)
                .map(Path::toString)
                .filter(relative -> !knownObjectKeys.contains(baseObjectKey(relative)))
                .forEach(relative -> {
                    try {
                        Files.deleteIfExists(path(relative.replace(java.io.File.separatorChar, '/')));
                    } catch (IOException exception) {
                        throw new IllegalStateException("Private receipt orphan cleanup failed", exception);
                    }
                });
        } catch (IOException exception) {
            throw new IllegalStateException("Private receipt orphan scan failed", exception);
        }
    }

    private String baseObjectKey(String relative) {
        if (relative.endsWith(".backup")) {
            return relative.substring(0, relative.length() - ".backup".length());
        }
        if (relative.endsWith(".version")) {
            return relative.substring(0, relative.length() - ".version".length());
        }
        return relative;
    }

    private Path path(String objectKey) {
        Path candidate = root.resolve(objectKey).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("Receipt object key escaped private root");
        }
        return candidate;
    }

    private void makePrivate(Path path) {
        try {
            Files.setPosixFilePermissions(path, PRIVATE_DIRECTORY_PERMISSIONS);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows has no POSIX mode; the root remains outside the web resource tree.
        }
    }
}
