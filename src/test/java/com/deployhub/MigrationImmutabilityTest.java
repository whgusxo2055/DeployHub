package com.deployhub;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

/**
 * 적용된 마이그레이션은 불변이다 — 파일이 바뀌면 이미 적용한 DB가 checksum mismatch로 기동을 못 한다.
 * Testcontainers는 매번 빈 DB를 띄워 이 사고를 절대 못 잡으므로(2026-08-26 실제 발생) 여기서 고정한다.
 */
class MigrationImmutabilityTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    // 새 마이그레이션을 추가할 때만 줄을 늘린다. 기존 값을 고쳐야 한다면 그건 파일을 잘못 건드린 것이다.
    private static final Map<String, Long> PINNED = Map.of("V1__init_schema.sql", 3233542121L);

    @Test
    void 적용된_마이그레이션_파일은_바뀌지_않았다() {
        assertThat(checksums()).containsExactlyInAnyOrderEntriesOf(PINNED);
    }

    /** 줄바꿈을 정규화하고 센다 — drvfs 워킹트리가 CRLF로 뒤집혀도 값이 흔들리면 안 된다. */
    private static Map<String, Long> checksums() {
        Map<String, Long> result = new LinkedHashMap<>();
        try (var files = Files.list(MIGRATIONS)) {
            files.filter(path -> path.toString().endsWith(".sql")).sorted().forEach(path -> {
                CRC32 crc = new CRC32();
                crc.update(normalize(path).getBytes(StandardCharsets.UTF_8));
                result.put(path.getFileName().toString(), crc.getValue());
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    private static String normalize(Path path) {
        try {
            return Files.readString(path).replace("\r\n", "\n").replace("\r", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
