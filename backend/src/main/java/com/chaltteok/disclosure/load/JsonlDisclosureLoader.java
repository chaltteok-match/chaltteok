package com.chaltteok.disclosure.load;

import com.chaltteok.disclosure.load.LoadSummary.LoadFailure;
import com.chaltteok.disclosure.model.ExtractedFact;
import com.chaltteok.disclosure.model.enums.ExtractionStatus;
import com.chaltteok.disclosure.model.PreprocessResult;
import com.chaltteok.disclosure.persistence.DisclosurePersistenceService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * UTF-8 JSON Lines 파일 한 줄을 {@link PreprocessResult} 한 건으로 읽어 적재한다.
 *
 * <p>한 줄이 실패해도 나머지를 계속 처리하고, 실패한 줄 번호와 {@code doc_id}를 모아
 * {@link LoadSummary}로 돌려준다. 실패를 조용히 넘기지 않기 위한 구조다
 * (docs/data-model.md §9).
 */
@Component
@ConditionalOnProperty(name = "chaltteok.disclosure.persistence.enabled", havingValue = "true")
public class JsonlDisclosureLoader {

    private static final Logger log = LoggerFactory.getLogger(JsonlDisclosureLoader.class);

    private final DisclosurePersistenceService persistenceService;
    private final Validator validator;
    private final ObjectMapper objectMapper;

    public JsonlDisclosureLoader(
            DisclosurePersistenceService persistenceService,
            Validator validator,
            ObjectMapper objectMapper
    ) {
        this.persistenceService = persistenceService;
        this.validator = validator;
        // 계약에 없는 필드가 섞이면 조용히 무시하지 않고 실패시킨다.
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public LoadSummary load(Path jsonlPath) {
        if (!Files.isRegularFile(jsonlPath)) {
            throw new JsonlLoadException("적재할 JSONL 파일이 없습니다: " + jsonlPath);
        }

        List<LoadFailure> failures = new ArrayList<>();
        // 같은 doc_id를 다시 만나면 저장은 upsert로 교체된다. 요약도 줄 단위로 더하지 않고
        // 문서 단위 최신값으로 집계해야 실제 적재 결과와 어긋나지 않는다.
        Map<String, SavedCounts> savedPerDocument = new LinkedHashMap<>();
        int totalRecords = 0;
        int succeeded = 0;

        try (BufferedReader reader = Files.newBufferedReader(jsonlPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                totalRecords++;
                try {
                    PreprocessResult result = read(line);
                    persistenceService.save(result);
                    succeeded++;
                    savedPerDocument.put(result.document().docId(), SavedCounts.of(result));
                } catch (RuntimeException exception) {
                    failures.add(new LoadFailure(lineNumber, docIdOf(line), reasonOf(exception)));
                }
            }
        } catch (IOException exception) {
            throw new JsonlLoadException("JSONL 파일을 읽지 못했습니다: " + jsonlPath, exception);
        }

        int chunks = 0;
        int facts = 0;
        int relations = 0;
        Map<ExtractionStatus, Integer> statusCounts = new EnumMap<>(ExtractionStatus.class);
        for (SavedCounts counts : savedPerDocument.values()) {
            chunks += counts.evidenceChunks();
            facts += counts.extractedFacts();
            relations += counts.relations();
            counts.statusCounts().forEach((status, count) -> statusCounts.merge(status, count, Integer::sum));
        }

        LoadSummary summary = new LoadSummary(
                totalRecords, succeeded, savedPerDocument.size(), chunks, facts, relations,
                statusCounts, failures);
        log.info("{}", summary.describe());
        return summary;
    }

    /** 문서 1건이 남긴 행 수. 같은 doc_id를 다시 적재하면 이 값으로 교체된다. */
    private record SavedCounts(
            int evidenceChunks,
            int extractedFacts,
            int relations,
            Map<ExtractionStatus, Integer> statusCounts
    ) {

        static SavedCounts of(PreprocessResult result) {
            Map<ExtractionStatus, Integer> counts = new EnumMap<>(ExtractionStatus.class);
            for (ExtractedFact fact : result.extractedFacts()) {
                counts.merge(fact.extractionStatus(), 1, Integer::sum);
            }
            return new SavedCounts(
                    result.evidenceChunks().size(),
                    result.extractedFacts().size(),
                    result.relations().size(),
                    counts);
        }
    }

    private PreprocessResult read(String line) {
        PreprocessResult result;
        try {
            result = objectMapper.readValue(line, PreprocessResult.class);
        } catch (IOException exception) {
            throw new JsonlLoadException("JSON 역직렬화 실패: " + reasonOf(exception), exception);
        }

        Set<ConstraintViolation<PreprocessResult>> violations = validator.validate(result);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw new JsonlLoadException("모델 검증 실패: " + detail);
        }
        return result;
    }

    /** 역직렬화 전이라도 실패한 줄을 식별할 수 있도록 doc_id만 따로 읽어 본다. */
    private String docIdOf(String line) {
        try {
            var node = objectMapper.readTree(line).path("document").path("doc_id");
            return node.isTextual() ? node.asText() : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private static final int MAX_REASON_LENGTH = 200;

    /** 실패 사유는 사람이 읽을 수 있어야 한다. SQL 전문·stack trace를 그대로 흘리지 않는다. */
    private static String reasonOf(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        String firstLine = newline == -1 ? message : message.substring(0, newline);
        if (firstLine.length() <= MAX_REASON_LENGTH) {
            return firstLine;
        }
        return firstLine.substring(0, MAX_REASON_LENGTH) + "… (생략)";
    }
}
