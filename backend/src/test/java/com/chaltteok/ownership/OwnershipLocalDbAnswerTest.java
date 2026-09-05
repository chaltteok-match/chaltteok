package com.chaltteok.ownership;

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
 * 로컬에 적재된 대량보유상황보고서로 단건 조회를 돌린다.
 * CI에서는 건너뛴다. {@code CHALTTEOK_LOCAL_DB_TEST=true}일 때만 실행한다.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "chaltteok.disclosure.persistence.enabled=true"
})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "CHALTTEOK_LOCAL_DB_TEST", matches = "true")
@Transactional(readOnly = true)
class OwnershipLocalDbAnswerTest {

    private static final DateTimeFormatter QUESTION_DATE =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일", Locale.KOREA);

    @Autowired
    private OwnershipAnswerHandler handler;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void answersRatioForLoadedHoldingFilingDates() {
        List<DisclosureDocumentEntity> documents = entityManager.createQuery(
                        """
                        select d from DisclosureDocumentEntity d
                        where d.docGroup = :docGroup and d.docSubtype = :subtype
                        """,
                        DisclosureDocumentEntity.class)
                .setParameter("docGroup", DocGroup.HOLDING)
                .setParameter("subtype", OwnershipAnswerHandler.DOC_SUBTYPE)
                .getResultList();
        assertThat(documents).isNotEmpty();

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
                    + " 공시한 대량보유상황보고서의 지분율은?";
            if (!handler.supports(question)) {
                unavailable++;
                continue;
            }
            AnswerHandlerResult result = handler.handle("Q-LOCAL", question);
            if (result.isAnswerable()) {
                answerable++;
            } else {
                unavailable++;
            }
        }
        System.out.printf(
                "대량보유 기업·공시일 %d쌍 지분율: 답변 %d, 답변 불가 %d%n",
                pairs.size(), answerable, unavailable);
        assertThat(answerable).isGreaterThan(0);
    }

    @Test
    void answersSamsungHoldingFromCorpusNotFixture() {
        String question = "삼성전자가 2023년 1월 20일 공시한 대량보유상황보고서의 지분율은?";
        assertThat(handler.supports(question)).isTrue();
        AnswerHandlerResult result = handler.handle("Q-LOCAL-SAMSUNG", question);
        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences().get(0).evidenceText()).contains("20.74");
    }

    @Test
    void doesNotAnswerMissingYearAsAnotherFiling() {
        List<String> corps = entityManager.createQuery(
                        """
                        select distinct d.corpName from DisclosureDocumentEntity d
                        where d.docGroup = :docGroup and d.docSubtype = :subtype
                        order by d.corpName
                        """,
                        String.class)
                .setParameter("docGroup", DocGroup.HOLDING)
                .setParameter("subtype", OwnershipAnswerHandler.DOC_SUBTYPE)
                .getResultList();
        assertThat(corps).isNotEmpty();

        int withoutDate = 0;
        int missingYear = 0;
        for (String corp : corps) {
            String noDate = corp + " 대량보유상황보고서의 지분율은?";
            if (handler.supports(noDate)
                    && handler.handle("Q-LOCAL-NODATE", noDate).isAnswerable()) {
                withoutDate++;
                System.out.println("  날짜 없이 답: " + corp);
            }
            String missingYearQuestion = corp + "의 2099년 대량보유상황보고서의 지분율은?";
            if (handler.supports(missingYearQuestion)
                    && handler.handle("Q-LOCAL-2099", missingYearQuestion).isAnswerable()) {
                missingYear++;
            }
        }
        System.out.printf(
                "대량보유 기업 %d: 날짜 없이 답 %d, 2099년으로 답 %d%n",
                corps.size(), withoutDate, missingYear);
        assertThat(missingYear).isZero();
    }
}
