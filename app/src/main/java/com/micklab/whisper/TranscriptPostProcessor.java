package com.micklab.whisper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class TranscriptPostProcessor {
    static final class Snapshot {
        final String displayText;
        final String plainText;

        Snapshot(String displayText, String plainText) {
            this.displayText = displayText;
            this.plainText = plainText;
        }
    }

    private static final class SubtitleLine {
        final long startTimeMs;
        final long endTimeMs;
        final String text;

        SubtitleLine(long startTimeMs, long endTimeMs, String text) {
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.text = text;
        }
    }

    private static final Map<Pattern, String> IT_TERM_REPLACEMENTS = new LinkedHashMap<>();

    static {
        IT_TERM_REPLACEMENTS.put(Pattern.compile("kubernetes", Pattern.CASE_INSENSITIVE), "Kubernetes");
        IT_TERM_REPLACEMENTS.put(Pattern.compile("ク[ー]?バ[ネニ]テ[ィイ]ス"), "Kubernetes");
        IT_TERM_REPLACEMENTS.put(Pattern.compile("コンテナー"), "コンテナ");
        IT_TERM_REPLACEMENTS.put(Pattern.compile("レプリカ\\s*セット"), "レプリカセット");
        IT_TERM_REPLACEMENTS.put(Pattern.compile("クラスター"), "クラスタ");
        IT_TERM_REPLACEMENTS.put(Pattern.compile("デプロイメント"), "デプロイメント");
    }

    private final List<SubtitleLine> subtitleLines = new ArrayList<>();
    private String plainTranscript = "";

    void reset() {
        subtitleLines.clear();
        plainTranscript = "";
    }

    Snapshot appendChunk(long startTimeMs, long endTimeMs, String rawChunkText) {
        String normalized = normalize(rawChunkText);
        if (normalized.isEmpty()) {
            return snapshot();
        }

        String deduplicated = removeLeadingOverlap(plainTranscript, normalized);
        deduplicated = normalize(deduplicated);
        if (deduplicated.isEmpty()) {
            return snapshot();
        }

        subtitleLines.add(new SubtitleLine(startTimeMs, endTimeMs, deduplicated));
        plainTranscript = mergePlainText(plainTranscript, deduplicated);
        return snapshot();
    }

    Snapshot snapshot() {
        return new Snapshot(buildDisplayText(), plainTranscript);
    }

    String getPlainTranscript() {
        return plainTranscript;
    }

    private String buildDisplayText() {
        if (subtitleLines.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < subtitleLines.size(); i++) {
            SubtitleLine line = subtitleLines.get(i);
            if (i > 0) {
                builder.append('\n');
            }
            builder.append('[')
                    .append(formatTimestamp(line.startTimeMs))
                    .append('-')
                    .append(formatTimestamp(line.endTimeMs))
                    .append("] ")
                    .append(line.text);
        }
        return builder.toString();
    }

    private String mergePlainText(String existing, String addition) {
        if (existing.isEmpty()) {
            return addition;
        }
        if (addition.isEmpty()) {
            return existing;
        }
        if (endsWithSentencePunctuation(existing) || startsWithSentencePunctuation(addition)) {
            return existing + addition;
        }
        return existing + addition;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }

        String normalized = text.trim();
        if (normalized.isEmpty()) {
            return "";
        }

        normalized = normalized.replace('\n', ' ');
        normalized = normalized.replaceAll("\\s+", " ");
        normalized = normalized.replace(",", "、");
        normalized = normalized.replace("，", "、");
        normalized = normalized.replace(".", "。");
        normalized = normalized.replace("．", "。");
        normalized = normalized.replace("!", "！");
        normalized = normalized.replace("?", "？");
        normalized = normalized.replaceAll("\\s*([、。！？])\\s*", "$1");
        normalized = normalized.replaceAll("([、。！？]){2,}", "$1");

        for (Map.Entry<Pattern, String> entry : IT_TERM_REPLACEMENTS.entrySet()) {
            normalized = entry.getKey().matcher(normalized).replaceAll(entry.getValue());
        }

        // 助詞補正は誤爆しやすいので、topic marker の誤認だけ高精度ルールで絞って補正します。
        normalized = normalized.replace("でわ", "では");
        normalized = normalized.replace("それわ", "それは");
        normalized = normalized.replace("これわ", "これは");
        normalized = normalized.replace("あれわ", "あれは");
        normalized = normalized.replace("私わ", "私は");
        normalized = normalized.replace("僕わ", "僕は");
        normalized = normalized.replace("俺わ", "俺は");

        normalized = collapseRepeatedWords(normalized);
        normalized = collapseRepeatedPhrase(normalized);
        normalized = applyTerminalPunctuation(normalized);
        return normalized.trim();
    }

    private String removeLeadingOverlap(String previous, String current) {
        if (previous.isEmpty() || current.isEmpty()) {
            return current;
        }

        String compactPrevious = compact(previous);
        String compactCurrent = compact(current);
        if (compactCurrent.isEmpty()) {
            return "";
        }
        if (compactPrevious.endsWith(compactCurrent)) {
            return "";
        }

        int maxOverlap = Math.min(compactPrevious.length(), compactCurrent.length());
        for (int overlap = maxOverlap; overlap >= 4; overlap--) {
            if (compactPrevious.regionMatches(
                    compactPrevious.length() - overlap,
                    compactCurrent,
                    0,
                    overlap
            )) {
                return dropCompactPrefix(current, overlap);
            }
        }
        return current;
    }

    private String collapseRepeatedWords(String text) {
        String collapsed = text;
        collapsed = collapsed.replaceAll("(?i)\\b([a-z0-9\\-]+)(?:\\s+\\1\\b)+", "$1");
        collapsed = collapsed.replaceAll("(.)\\1{3,}", "$1$1");
        return collapsed;
    }

    private String collapseRepeatedPhrase(String text) {
        String compact = compact(text);
        if (compact.length() < 4) {
            return text;
        }

        for (int unitLength = compact.length() / 2; unitLength >= 2; unitLength--) {
            if (compact.length() % unitLength != 0) {
                continue;
            }

            int repeatCount = compact.length() / unitLength;
            if (repeatCount < 2) {
                continue;
            }

            String unit = compact.substring(0, unitLength);
            boolean repeated = true;
            for (int repeatIndex = 1; repeatIndex < repeatCount; repeatIndex++) {
                int start = repeatIndex * unitLength;
                if (!compact.regionMatches(start, unit, 0, unitLength)) {
                    repeated = false;
                    break;
                }
            }
            if (repeated) {
                return dropCompactSuffix(text, compact.length() - unitLength);
            }
        }
        return text;
    }

    private String applyTerminalPunctuation(String text) {
        if (text.isEmpty() || endsWithSentencePunctuation(text)) {
            return text;
        }

        if (text.matches(".*(です|ます|でした|ません|ください|した|します|になります|でしたか|ですか)$")) {
            return text + '。';
        }
        return text;
    }

    private String compact(String text) {
        return text.replaceAll("[\\s、。！？]", "");
    }

    private String dropCompactPrefix(String original, int compactCharsToDrop) {
        int compactSeen = 0;
        int index = 0;
        while (index < original.length() && compactSeen < compactCharsToDrop) {
            char current = original.charAt(index);
            if (!Character.isWhitespace(current) && !isSentencePunctuation(current)) {
                compactSeen++;
            }
            index++;
        }
        return original.substring(Math.min(index, original.length())).trim();
    }

    private String dropCompactSuffix(String original, int compactCharsToDropFromEnd) {
        if (compactCharsToDropFromEnd <= 0) {
            return original;
        }

        int compactSeen = 0;
        int index = original.length();
        while (index > 0 && compactSeen < compactCharsToDropFromEnd) {
            char current = original.charAt(index - 1);
            if (!Character.isWhitespace(current) && !isSentencePunctuation(current)) {
                compactSeen++;
            }
            index--;
        }
        return original.substring(0, Math.max(0, index)).trim();
    }

    private boolean endsWithSentencePunctuation(String text) {
        if (text.isEmpty()) {
            return false;
        }
        return isSentencePunctuation(text.charAt(text.length() - 1));
    }

    private boolean startsWithSentencePunctuation(String text) {
        if (text.isEmpty()) {
            return false;
        }
        return isSentencePunctuation(text.charAt(0));
    }

    private boolean isSentencePunctuation(char value) {
        return value == '。' || value == '、' || value == '！' || value == '？';
    }

    private String formatTimestamp(long timestampMs) {
        long totalSeconds = Math.max(0L, timestampMs / 1_000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.JAPAN, "%02d:%02d", minutes, seconds);
    }
}
