package com.github.guoyyit.portfolioattributionjava.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * 仅追加、带校验和的事件日志。每条记录原子落盘，
 * 重启时全量重放恢复状态，保证无部分生效的写入。
 *
 * <p>帧格式：8 字节魔数 | 8 字节负载长度 | JSON 负载 | 8 字节 CRC32（小端无关，统一大端）。</p>
 */
@Component
public class EventLog {

    private static final long MAGIC = 0x50414C454C4F4731L;

    private final Path logFile;
    private final ObjectMapper objectMapper;
    private final Object writeLock = new Object();

    public EventLog(@Value("${attribution.data-dir:data}") String dataDir,
                    ObjectMapper objectMapper) {
        this.logFile = Path.of(dataDir, "event.log");
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(Path.of(dataDir));
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建数据目录: " + dataDir, e);
        }
    }

    /** 追加一条事件（JSON），fsync 后返回。 */
    public void append(JsonNode event) {
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(event);
        } catch (IOException e) {
            throw new UncheckedIOException("事件序列化失败", e);
        }
        CRC32 crc = new CRC32();
        crc.update(payload);
        byte[] framed = new byte[24 + payload.length];
        putLong(framed, 0, MAGIC);
        putLong(framed, 8, payload.length);
        System.arraycopy(payload, 0, framed, 16, payload.length);
        putLong(framed, 16 + payload.length, crc.getValue());
        synchronized (writeLock) {
            try (FileOutputStream out = new FileOutputStream(logFile.toFile(), true)) {
                out.write(framed);
                out.flush();
                out.getFD().sync();
            } catch (IOException e) {
                throw new UncheckedIOException("事件日志写入失败", e);
            }
        }
    }

    /** 读取全部有效事件（截断/损坏的尾部记录被忽略）。 */
    public List<JsonNode> replay() {
        byte[] bytes;
        try {
            if (!Files.exists(logFile)) {
                return List.of();
            }
            bytes = Files.readAllBytes(logFile);
        } catch (IOException e) {
            throw new UncheckedIOException("事件日志读取失败", e);
        }
        List<JsonNode> events = new ArrayList<>();
        int offset = 0;
        while (offset + 24 <= bytes.length) {
            long magic = getLong(bytes, offset);
            int length = (int) getLong(bytes, offset + 8);
            if (magic != MAGIC || length < 0 || offset + 24L + length > bytes.length) {
                break;
            }
            long expectedCrc = getLong(bytes, offset + 16 + length);
            CRC32 crc = new CRC32();
            crc.update(bytes, offset + 16, length);
            if (crc.getValue() != expectedCrc) {
                break;
            }
            try {
                JsonNode node = objectMapper.readTree(
                        Arrays.copyOfRange(bytes, offset + 16, offset + 16 + length));
                events.add(node);
            } catch (IOException e) {
                break;
            }
            offset += 24 + length;
        }
        return events;
    }

    private static void putLong(byte[] buffer, int offset, long value) {
        for (int i = 7; i >= 0; i--) {
            buffer[offset + i] = (byte) (value & 0xff);
            value >>= 8;
        }
    }

    private static long getLong(byte[] buffer, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (buffer[offset + i] & 0xffL);
        }
        return value;
    }
}
