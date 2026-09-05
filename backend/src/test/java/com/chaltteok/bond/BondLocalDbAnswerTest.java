package com.chaltteok.bond;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaltteok.answer.AnswerHandlerResult;
import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로컬에 적재된 전환·교환사채 발행결정으로 Handler를 돌린다.
 * CI에서는 건너뛴다. {@code CHALTTEOK_LOCAL_DB_TEST=true}일 때만 실행한다.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "chaltteok.disclosure.persistence.enabled=true"
})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "CHALTTEOK_LOCAL_DB_TEST", matches = "true")
@Transactional(readOnly = true)
class BondLocalDbAnswerTest {

    private static final DateTimeFormatter QUESTION_DATE =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일", Locale.KOREA);

    @Autowired
    private BondIssuanceAnswerHandler handler;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void answersEveryLoadedConvertibleAndExchangeableFilingDate() {
        int convertible = probe(BondForm.CONVERTIBLE, "전환사채");
        int exchangeable = probe(BondForm.EXCHANGEABLE, "교환사채");
        assertThat(convertible + exchangeable).isGreaterThan(0);
    }

    private int probe(BondForm form, String questionLabel) {
        List<DisclosureDocumentEntity> documents = entityManager.createQuery(
                        """
                        select d from DisclosureDocumentEntity d
                        where d.docGroup = :docGroup and d.docSubtype = :subtype
                        """,
                        DisclosureDocumentEntity.class)
                .setParameter("docGroup", DocGroup.MAJOR)
                .setParameter("subtype", form.docSubtype())
                .getResultList();
        assertThat(documents).as(form.docSubtype() + " 적재").isNotEmpty();

        Set<String> pairs = new LinkedHashSet<>();
        int answerable = 0;
        int unavailable = 0;
        for (DisclosureDocumentEntity document : documents) {
            String key = document.getCorpName() + "|" + document.getRceptDt();
            if (!pairs.add(key)) {
                continue;
            }
            LocalDate date = document.getRceptDt();
            String question = document.getCorpName() + "가 " + date.format(QUESTION_DATE)
                    + " 공시한 " + questionLabel + "의 권면총액은 얼마인가?";
            assertThat(handler.supports(question)).isTrue();
            AnswerHandlerResult result = handler.handle("Q-LOCAL", question);
            if (result.isAnswerable()) {
                answerable++;
                assertThat(result.evidences()).isNotEmpty();
            } else {
                unavailable++;
            }
        }
        System.out.printf(
                "%s 기업·공시일 %d쌍 권면총액: 답변 %d, 답변 불가 %d%n",
                form.docSubtype(), pairs.size(), answerable, unavailable);
        return answerable;
    }
}
