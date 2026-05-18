package org.example.voice_assistant.rag;

import org.example.voice_assistant.config.DocumentChunkConfig;
import org.example.voice_assistant.rag.entity.DocumentChunk;
import org.example.voice_assistant.rag.service.DocumentChunkService;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 端到端分片验证：用实际上传的 .md 文档测试新 chunk pipeline。
 */
public class ChunkingTest {

    private static final Path UPLOADS = Paths.get("uploads");

    public static void main(String[] args) throws Exception {
        DocumentChunkConfig config = new DocumentChunkConfig();
        config.setTargetSize(500);
        config.setMaxSize(800);
        config.setMinSize(100);
        config.setOverlapTokens(80);

        DocumentChunkService service = new DocumentChunkService();
        Field configField = DocumentChunkService.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(service, config);

        List<Path> mdFiles = new ArrayList<>();
        try (var stream = Files.list(UPLOADS)) {
            stream.filter(f -> f.toString().endsWith(".md"))
                  .sorted(Comparator.comparing(Path::getFileName))
                  .forEach(mdFiles::add);
        }

        int totalDocs = 0;
        int totalChunks = 0;
        int titleOnlyChunks = 0;
        int tooShortChunks = 0;

        for (Path file : mdFiles) {
            totalDocs++;
            String content = Files.readString(file);
            List<DocumentChunk> chunks = service.chunkDocument(content, file.getFileName().toString());

            System.out.println("\n═══════════════════════════════════════════");
            System.out.println("文件: " + file.getFileName());
            System.out.println("分片数: " + chunks.size());
            System.out.println("───────────────────────────────────────────");

            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk c = chunks.get(i);
                String chunkContent = c.getContent();
                int tokenCount = org.example.voice_assistant.rag.service.ChunkTokenizer.countTokens(chunkContent);

                System.out.printf("  [Chunk %d] tokens=%d, chars=%d, title=%s%n",
                        i, tokenCount, chunkContent.length(),
                        c.getTitle() != null ? c.getTitle() : "(null)");

                // 检查：有 title 但 body 部分为空或极短（< 15 chars）
                if (c.getTitle() != null && !c.getTitle().isEmpty()) {
                    String bodyOnly = chunkContent;
                    // 尝试去除 title 前缀
                    if (bodyOnly.startsWith(c.getTitle())) {
                        bodyOnly = bodyOnly.substring(c.getTitle().length()).trim();
                    }
                    if (bodyOnly.length() < 15) {
                        System.out.printf("    ⚠️  【纯标题/体太短】body 仅 %d chars: \"%s\"%n",
                                bodyOnly.length(), truncate(chunkContent, 80));
                        titleOnlyChunks++;
                    }
                }

                if (chunkContent.length() < 20) {
                    System.out.printf("    ⚠️  【过短】< 20 chars: \"%s\"%n", truncate(chunkContent, 80));
                    tooShortChunks++;
                }

                System.out.printf("    预览: %s%n", truncate(chunkContent, 120));
            }

            totalChunks += chunks.size();
        }

        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("总结:");
        System.out.println("  处理文档: " + totalDocs + " 个");
        System.out.println("  总分片数: " + totalChunks + " 个");
        System.out.println("  纯标题分片(body<15chars): " + titleOnlyChunks + " 个");
        System.out.println("  过短分片 (<20 chars): " + tooShortChunks + " 个");

        if (titleOnlyChunks > 0 || tooShortChunks > 0) {
            System.out.println("\n❌ 测试失败！存在不合理的分片。");
            System.exit(1);
        } else {
            System.out.println("\n✅ 全部通过！未发现纯标题或过短分片。");
        }
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
