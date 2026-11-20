package com.example.chat.controller;

import com.example.chat.dto.UploadResponse;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * File uploads with strict validation:
 *  - max 10 MB (also enforced by the multipart resolver)
 *  - extension allow-list (images + common documents)
 *  - stored under a random UUID name -> unguessable capability URLs
 *  - download path is allow-listed by regex AND containment-checked (no traversal)
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final Set<String> FILE_EXTENSIONS =
            Set.of("pdf", "txt", "md", "doc", "docx", "xls", "xlsx", "csv", "ppt", "pptx", "zip");
    private static final Map<String, MediaType> MEDIA_TYPES = Map.ofEntries(
            Map.entry("jpg", MediaType.IMAGE_JPEG),
            Map.entry("jpeg", MediaType.IMAGE_JPEG),
            Map.entry("png", MediaType.IMAGE_PNG),
            Map.entry("gif", MediaType.IMAGE_GIF),
            Map.entry("webp", MediaType.parseMediaType("image/webp")),
            Map.entry("pdf", MediaType.APPLICATION_PDF),
            Map.entry("txt", MediaType.TEXT_PLAIN),
            Map.entry("md", MediaType.TEXT_PLAIN),
            Map.entry("csv", MediaType.parseMediaType("text/csv")),
            Map.entry("zip", MediaType.parseMediaType("application/zip")),
            Map.entry("doc", MediaType.APPLICATION_OCTET_STREAM),
            Map.entry("docx", MediaType.APPLICATION_OCTET_STREAM),
            Map.entry("xls", MediaType.APPLICATION_OCTET_STREAM),
            Map.entry("xlsx", MediaType.APPLICATION_OCTET_STREAM),
            Map.entry("ppt", MediaType.APPLICATION_OCTET_STREAM),
            Map.entry("pptx", MediaType.APPLICATION_OCTET_STREAM));

    /** UUID v4 + allow-listed extension — anything else is rejected before touching the filesystem. */
    private static final String STORED_NAME_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]+$";

    private final Path uploadDir;

    public FileController(@Value("${app.upload-dir}") String uploadDir) {
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(uploadDir);
    }

    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the 10MB limit");
        }
        String originalName = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String extension = extensionOf(originalName);
        boolean isImage = IMAGE_EXTENSIONS.contains(extension);
        if (!isImage && !FILE_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "File type not allowed. Allowed: images (jpg, png, gif, webp) and documents (pdf, txt, md, doc(x), xls(x), csv, ppt(x), zip)");
        }

        String storedName = UUID.randomUUID() + "." + extension;
        Path target = uploadDir.resolve(storedName).normalize();
        if (!target.startsWith(uploadDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file name");
        }
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store file");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(new UploadResponse(
                "/api/files/" + storedName, originalName, isImage ? "IMAGE" : "FILE", file.getSize()));
    }

    @GetMapping("/{filename}")
    public ResponseEntity<Resource> download(@PathVariable String filename) {
        if (!filename.matches(STORED_NAME_PATTERN)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }
        Path target = uploadDir.resolve(filename).normalize();
        if (!target.startsWith(uploadDir) || !Files.isRegularFile(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }

        String extension = extensionOf(filename);
        MediaType mediaType = MEDIA_TYPES.getOrDefault(extension, MediaType.APPLICATION_OCTET_STREAM);
        boolean inline = IMAGE_EXTENSIONS.contains(extension) || "pdf".equals(extension) || "txt".equals(extension);
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(filename)
                .build();
        try {
            long size = Files.size(target);
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(size)
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                    // Belt and braces: never let a browser sniff a different content type.
                    .header("X-Content-Type-Options", "nosniff")
                    .body(new InputStreamResource(Files.newInputStream(target)));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read file");
        }
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
