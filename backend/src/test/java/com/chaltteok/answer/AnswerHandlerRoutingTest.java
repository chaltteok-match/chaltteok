package com.chaltteok.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.persistence.repository.CorpIdentityRepository;
import com.chaltteok.disclosure.persistence.repository.DisclosureDocumentRepository;
import com.chaltteok.disclosure.persistence.repository.DisclosureRelationRepository;
import com.chaltteok.disclosure.persistence.repository.ExtractedFactRepository;
import com.chaltteok.exchange.ExchangeAnswerHandler;
import com.chaltteok.financial.FinancialAnswerHandler;
import com.chaltteok.fundraising.FundraisingAnswerHandler;
import com.chaltteok.ownership.OwnershipAnswerHandler;
import com.chaltteok.treasury.TreasuryStockAnswerHandler;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

/**
 * Handler 다섯을 한 자리에 올려놓고 질문이 제 유형으로 가는지 본다.
 *
 * <p>{@link AnswerHandlerSelector}는 {@code supports()}가 처음 참인 것을 집으므로, 질문이 유형
 * 두 개를 가리키면 더 구체적인 쪽이 아니라 {@code @Order}가 앞선 쪽이 가져간다. 라우팅이
 * 어긋나도 예외가 나지 않고 엉뚱한 Handler의 답이 그대로 나가기 때문에 눈에 띄지 않는다.
 *
 * <p>등록 순서는 스프링이 하듯 {@link AnnotationAwareOrderComparator}로 만든다. 순서를 손으로
 * 적으면 {@code @Order} 값을 바꿔도 이 테스트가 눈치채지 못한다.
 *
 * <p>기업은 유형별로 갈라 둔다. 실제로도 {@code CorpIdentityRepository}가 유형별로 좁혀 주므로,
 * 그 유형 문서가 없는 기업이면 Handler가 애초에 나서지 않는다. 한화에어로스페이스만 유상증자·
 * 지분공시·거래소공시를 함께 갖는데, **한 기업이 두 유형을 다 가질 때가 가로채기가 드러나는
 * 자리다.**
 */
@ExtendWith(MockitoExtension.class)
class AnswerHandlerRoutingTest {

    private static final CorpIdentity SAMSUNG =
            new CorpIdentity("삼성전자", "삼성전자", "005930", "00126380");
    private static final CorpIdentity HANWHA =
            new CorpIdentity("한화에어로스페이스", "한화에어로", "012450", "00164788");
    private static final CorpIdentity SK_HYNIX =
            new CorpIdentity("SK하이닉스", "SK하이닉스", "000660", "00164779");

    @Mock
    private DisclosureDocumentRepository documentRepository;

    @Mock
    private ExtractedFactRepository factRepository;

    @Mock
    private DisclosureRelationRepository relationRepository;

    @Mock
    private CorpIdentityRepository corpIdentityRepository;

    private AnswerHandlerSelector selector;

    @BeforeEach
    void setUp() {
        // 로컬 적재 현황을 그대로 옮긴다. 한화에어로스페이스만 네 유형을 다 가진다.
        // 서식명은 각 Handler의 DOC_SUBTYPE과 같다. 패키지 밖이라 문자열로 둔다.
        lenient().when(corpIdentityRepository.findByDocGroup(DocGroup.EXCHANGE))
                .thenReturn(List.of(SAMSUNG, HANWHA));
        lenient().when(corpIdentityRepository.findByDocGroup(DocGroup.PERIODIC))
                .thenReturn(List.of(SAMSUNG, HANWHA, SK_HYNIX));
        lenient().when(corpIdentityRepository.findByDocGroupAndDocSubtype(
                        DocGroup.MAJOR, "유상증자결정"))
                .thenReturn(List.of(HANWHA));
        lenient().when(corpIdentityRepository.findByDocGroupAndDocSubtype(
                        DocGroup.MAJOR, "자기주식취득결정"))
                .thenReturn(List.of(SAMSUNG));
        lenient().when(corpIdentityRepository.findByDocGroupAndDocSubtype(
                        DocGroup.HOLDING, "대량보유상황보고서"))
                .thenReturn(List.of(SAMSUNG, HANWHA, SK_HYNIX));

        List<AnswerHandler> handlers = new ArrayList<>(List.of(
                new FinancialAnswerHandler(
                        documentRepository, factRepository, corpIdentityRepository),
                new OwnershipAnswerHandler(
                        documentRepository, factRepository, corpIdentityRepository),
                new ExchangeAnswerHandler(documentRepository, factRepository,
                        relationRepository, corpIdentityRepository),
                new TreasuryStockAnswerHandler(
                        documentRepository, factRepository, corpIdentityRepository),
                new FundraisingAnswerHandler(
                        documentRepository, factRepository, corpIdentityRepository)));
        AnnotationAwareOrderComparator.sort(handlers);
        selector = new AnswerHandlerSelector(handlers);
    }

    private String routeOf(String question) {
        return selector.select(question)
                .map(handler -> handler.getClass().getSimpleName())
                .orElse("(없음)");
    }

    @Test
    void registersHandlersInDeclaredOrder() {
        assertThat(selector.hasRegisteredHandlers()).isTrue();
        assertThat(routeOf("삼성전자 공급계약 계약금액은?")).isEqualTo("ExchangeAnswerHandler");
        assertThat(routeOf("한화에어로스페이스 유상증자 조달금액은?"))
                .isEqualTo("FundraisingAnswerHandler");
        assertThat(routeOf("삼성전자 자기주식 취득 예정 주식 수는?"))
                .isEqualTo("TreasuryStockAnswerHandler");
        assertThat(routeOf("SK하이닉스 대량보유상황보고서 지분율은?"))
                .isEqualTo("OwnershipAnswerHandler");
        assertThat(routeOf("SK하이닉스 2024년 매출액은?")).isEqualTo("FinancialAnswerHandler");
    }

    @ParameterizedTest(name = "[{index}] {1} -> {0}")
    @CsvSource(delimiter = '|', textBlock = """
            ExchangeAnswerHandler        | 삼성전자가 2025년 7월 28일 공시한 단일판매·공급계약의 계약금액은 얼마인가?
            ExchangeAnswerHandler        | 삼성전자 신규시설투자의 투자금액은?
            ExchangeAnswerHandler        | 한화에어로스페이스 공급계약 해지 금액은?
            FundraisingAnswerHandler     | 한화에어로스페이스 유상증자로 조달하는 금액은?
            FundraisingAnswerHandler     | 한화에어로스페이스 유상증자에서 시설자금은 얼마인가?
            FundraisingAnswerHandler     | 한화에어로스페이스 유상증자 신주 발행가액은?
            TreasuryStockAnswerHandler   | 삼성전자 자기주식 취득 예정 금액은?
            OwnershipAnswerHandler       | 한화에어로스페이스 대량보유상황보고서의 보유 목적은?
            OwnershipAnswerHandler       | 삼성전자 지분공시 보고구분은?
            FinancialAnswerHandler       | 삼성전자 2024년 연결 매출액은?
            FinancialAnswerHandler       | 삼성전자 2024년 영업이익은 2023년 대비 얼마나 늘었나?
            FinancialAnswerHandler       | SK하이닉스 2024년 자산총계는?
            """)
    void routesEachQuestionToItsOwnType(String expected, String question) {
        assertThat(routeOf(question)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] {1} -> {0}")
    @CsvSource(delimiter = '|', textBlock = """
            FundraisingAnswerHandler | 한화에어로스페이스가 유상증자로 조달한 자금 중 시설투자에 쓰는 금액은?
            FundraisingAnswerHandler | 한화에어로스페이스 유상증자 시설투자 자금은?
            FundraisingAnswerHandler | 한화에어로스페이스 유상증자 투자금액은?
            OwnershipAnswerHandler   | 한화에어로스페이스 유상증자 참여 이후 대량보유 지분율은?
            OwnershipAnswerHandler   | 한화에어로스페이스 대량보유 보고자의 공급계약 관련 보유 목적은?
            """)
    void doesNotLetAnEarlierHandlerTakeAQuestionThatNamesAnotherType(
            String expected, String question) {
        // 순위가 앞선다고 가져가면 안 된다. 유상증자·대량보유처럼 유형을 못 박은 말이 이긴다.
        assertThat(routeOf(question)).isEqualTo(expected);
    }

    @Test
    void keepsExchangeAnswerWhenTheQuestionOnlyComparesAgainstRevenue() {
        // `매출액`은 유형을 못 박는 말이 아니다. 거래소공시가 매출액대비 비율을 담고 있으므로
        // 이 질문은 계속 거래소공시가 답해야 한다.
        assertThat(routeOf("삼성전자 공급계약의 계약금액은 최근 매출액의 몇 퍼센트인가?"))
                .isEqualTo("ExchangeAnswerHandler");
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            삼성전자 주가가 오를까?
            존재하지않는회사의 매출액은?
            한화에어로스페이스 자기주식 취득 예정 금액은?
            """)
    void selectsNothingWhenNoHandlerCanAnswer(String question) {
        // 마지막 줄은 한화에어로스페이스에 자기주식취득결정이 없어서다. 답할 수 없는 것을
        // 다른 Handler가 주워 가지 않아야 한다.
        assertThat(selector.select(question)).isEmpty();
    }
}
