package org.example.voice_assistant.rag.service;

/**
 * Token 计数工具。
 * 使用启发式算法估算中文/英文混合文本的 token 数量，
 * 避免引入重量级 tokenizer 依赖。
 *
 * 估算规则：
 *   - CJK 字符（含中文、日文汉字）：1 字符 ≈ 1 token
 *   - 英文单词：1 单词 ≈ 1.3 token
 *   - 数字/标点：按实际 token 近似 1:1
 *
 * 参考阿里云 text-embedding-v1 的 BPE 分词行为。
 */
public final class ChunkTokenizer {

    private ChunkTokenizer() {}

    /**
     * 估算文本的 token 数量。
     */
    public static int countTokens(String text) {
        if (text == null || text.isEmpty()) return 0;

        int tokens = 0;
        boolean inWord = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (isCjk(c)) {
                tokens++;
                inWord = false;
            } else if (Character.isWhitespace(c)) {
                // CJK 文本中空格通常不计 token，英文中空格分隔单词
                if (inWord) {
                    tokens++; // 单词结束，计 1 token
                    inWord = false;
                }
                // CJK 后的空格不计
            } else if (Character.isLetter(c)) {
                inWord = true;
            } else {
                // 标点、数字等：单独计 token
                if (inWord) {
                    tokens++; // 前面的英文单词结束
                    inWord = false;
                }
                tokens++;
            }
        }

        // 尾部未闭合的英文单词
        if (inWord) tokens++;

        return Math.max(1, tokens);
    }

    /**
     * 按 token 数截取文本末尾的 N 个完整句子作为 overlap。
     *
     * @param text        源文本
     * @param maxTokens   目标 overlap 的最大 token 数
     * @return 末尾的完整句子（不超过 maxTokens）
     */
    public static String extractLastSentences(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) return "";

        String[] sentences = splitSentences(text);
        StringBuilder result = new StringBuilder();
        int tokenCount = 0;

        // 从后往前取句子
        for (int i = sentences.length - 1; i >= 0; i--) {
            String s = sentences[i];
            int sTokens = countTokens(s);
            if (tokenCount + sTokens > maxTokens && tokenCount > 0) break;
            result.insert(0, s);
            tokenCount += sTokens;
        }

        return result.toString();
    }

    /**
     * 按句子分割文本（中文句号/问号/感叹号 + 英文标点）。
     */
    public static String[] splitSentences(String text) {
        if (text == null || text.isEmpty()) return new String[0];
        // 在句末标点后分割，保留标点在句子中
        return text.split("(?<=[。！？!?.])\\s*");
    }

    /**
     * 按段落分割（双换行及以上）。
     */
    public static String[] splitParagraphs(String text) {
        if (text == null || text.isEmpty()) return new String[0];
        return text.split("\\n{2,}");
    }

    // ---- internal ----

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)   // CJK Unified Ideographs
            || (c >= 0x3400 && c <= 0x4DBF)   // CJK Unified Ideographs Extension A
            || (c >= 0x20000 && c <= 0x2A6DF) // CJK Unified Ideographs Extension B
            || (c >= 0xF900 && c <= 0xFAFF)   // CJK Compatibility Ideographs
            || (c >= 0x3040 && c <= 0x309F)   // Hiragana
            || (c >= 0x30A0 && c <= 0x30FF)   // Katakana
            || (c >= 0xAC00 && c <= 0xD7AF);  // Hangul
    }
}
