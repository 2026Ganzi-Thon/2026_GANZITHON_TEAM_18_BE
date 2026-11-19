package kr.malsai.simulation;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@RestController
@RequestMapping("/api/v1/simulations")
class SimulationController {
    private static final int MIN_TURNS = 3;
    private static final int MAX_TURNS = 20;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ROLEPLAY_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd(E)", Locale.KOREAN);
    private static final int SCORE_SCALE = 100;
    private static final int MAX_BODY_BYTES = 96 * 1024;
    private static final int MAX_MESSAGE_LENGTH = 1500;
    private static final String ROLE = """
            requesterName은 일을 부탁한 상대의 이름이고 recipientName은 부탁을 받은 사용자 이름이다.
            부탁한 상대가 recipientName에게 하는 말만 한국어 한두 문장으로 쓴다.
            상대는 requestContent의 일을 부탁한 사람이다. 그 일을 맡겠다고 답하는 쪽은 사용자다.
            사용자의 가능 시간을 상대 본인의 가능 시간처럼 말하지 않는다.
            상대의 관계, 성격, 말투와 situationType을 반영한다.
            scenario.currentDate는 한국 표준시 기준 오늘 날짜다. deadlineText의 상대적 날짜나 요일을 말할 때 이 날짜를 기준으로 판단한다.
            """;
    private static final String QUICK_REPLIES = """
            suggestedReplies에는 사용자가 다음에 보낼 수 있는 서로 다른 짧은 한국어 문장을 최대 3개 넣는다.
            사용자 관점으로 쓰고, 제시된 목표와 조건을 지키며 새로운 일정이나 약속을 만들지 않는다.
            replyContext는 추천 문장에만 사용하고 상대의 메시지에는 사용하지 않는다.
            대화가 끝났거나 적절한 제안이 없으면 빈 배열로 둔다.
            """;
    private final SimulationAi ai;
    private final ObjectReader requestReader;

    SimulationController(SimulationAi ai, ObjectMapper json) {
        this.ai = ai;
        this.requestReader = json.readerFor(JsonNode.class).with(
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    @PostMapping(path = "/start", consumes = MediaType.APPLICATION_JSON_VALUE)
    StartResponse start(HttpServletRequest request) {
        JsonNode body = readBody(request);
        JsonNode setup = setup(body);
        JsonNode result = ai.generate(ROLE + """
                첫 메시지에서 부탁한 상대가 recipientName에게 말을 건다.
                이름을 부를 때는 recipientName을 사용한다.
                사용자의 답변이나 가능 시간을 예상해서 언급하지 않는다.
                GENERAL_REQUEST면 상대가 처음 부탁하는 말로 시작한다.
                RE_PERSUASION이면 상대가 다시 부탁하는 말로 시작하되 사용자의 이전 발언을 지어내지 않는다.
                첫 발화를 {firstAiMessage: 문자열, suggestedReplies: 문자열 배열} 형태의 JSON으로 출력한다.
                """ + QUICK_REPLIES,
                Map.of("scenario", roleplayContext(setup), "replyContext", replyContext(setup)),
                Map.of("firstAiMessage", "이번 부탁을 좀 도와줄 수 있어?", "suggestedReplies",
                        List.of("어떤 부분을 도와드리면 될까요?", "제가 가능한 시간부터 말씀드릴게요.")));
        return new StartResponse(outputText(result, "firstAiMessage"), MAX_TURNS, suggestedReplies(result));
    }

    @PostMapping(path = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    ChatResponse chat(HttpServletRequest request) {
        JsonNode body = readBody(request);
        JsonNode setup = setup(body);
        List<String> userMessages = messages(body, "USER");
        boolean limit = userMessages.size() >= MAX_TURNS;
        JsonNode result = ai.generate(ROLE + """
                대화의 AI 발화는 부탁한 상대의 말이고 USER 발화는 recipientName의 말이다.
                마지막 USER 발화에 부탁한 상대의 입장에서 직접 대답한다.
                aiMessage를 작성한 뒤 그 답변에 맞춰 shouldEnd를 결정한다.
                사용자가 밝힌 조건을 상대가 그대로 수용하거나 거절을 받아들여 대화를 마무리하면 shouldEnd는 true다.
                조건을 다시 제안하거나 답변을 기다리는 질문이 남으면 shouldEnd는 false다.
                예: USER가 '토요일 오전 두 시간만 가능해요'라고 한 경우
                {"aiMessage":"알겠어, 그럼 토요일 오전에 가능한 만큼만 부탁할게.","shouldEnd":true}
                예: 상대가 다른 조건을 제안해 사용자의 답을 기다리는 경우
                {"aiMessage":"오전 두 시간으로는 빠듯한데, 자료 요약만 맡아줄 수 있어?","shouldEnd":false}
                turnLimitReached가 true라면 마무리 발화를 한다.
                {aiMessage: 문자열, shouldEnd: 불리언, suggestedReplies: 문자열 배열} 형태의 JSON으로 출력한다.
                """ + QUICK_REPLIES, Map.of("scenario", roleplayContext(setup), "messages", modelMessages(body),
                        "turnLimitReached", limit, "replyContext", replyContext(setup)),
                Map.of("aiMessage", limit ? "알겠어. 여기서 마무리하자." : "그럼 어디까지 도와줄 수 있어?",
                        "shouldEnd", limit, "suggestedReplies", List.of("어떤 부분이 가장 급한가요?", "제가 가능한 범위만 먼저 말씀드릴게요.")));
        if (!result.path("shouldEnd").isBoolean()) throw SimulationFailure.aiError();
        boolean ended = userMessages.size() >= MIN_TURNS && result.path("shouldEnd").asBoolean();
        return new ChatResponse(outputText(result, "aiMessage"), limit || ended, limit ? "MAX_TURNS" : ended ? "AI" : null,
                limit || ended ? List.of() : suggestedReplies(result));
    }

    @PostMapping(path = "/hints", consumes = MediaType.APPLICATION_JSON_VALUE)
    HintResponse hints(HttpServletRequest request) {
        JsonNode body = readBody(request);
        JsonNode setup = setup(body);
        messages(body, "AI");
        JsonNode result = ai.generate("""
                사용자 목표에 맞는 한국어 표현 방향과 키워드를 제시한다. 완성 문장이나 새로운 약속을 제안하지 않는다.
                {direction: 문자열, keywords: 문자열 배열} 형태의 JSON으로 출력한다.
                """, Map.of("goal", setup.path("goal"), "messages", modelMessages(body)),
                Map.of("direction", "가능한 범위를 한 번 더 분명하게 전달해 보세요.", "keywords", List.of("가능한 범위", "마감")));
        List<String> keywords = new ArrayList<>();
        for (JsonNode keyword : outputArray(result, "keywords")) {
            if (!hasText(keyword)) throw SimulationFailure.aiError();
            keywords.add(keyword.asText());
        }
        return new HintResponse(outputText(result, "direction"), keywords);
    }

    @PostMapping(path = "/report", consumes = MediaType.APPLICATION_JSON_VALUE)
    ReportResponse report(HttpServletRequest request) {
        JsonNode body = readBody(request);
        JsonNode setup = setup(body);
        List<String> userMessages = messages(body, null);
        requireChoice(body, "endReason", Set.of("USER", "AI", "MAX_TURNS"));
        if (userMessages.isEmpty()) {
            return new ReportResponse(0, SCORE_SCALE, "평가할 사용자 발화가 없습니다.", List.of(), List.of());
        }
        String responseType = setup.path("conflictContext").path("responseType").asText();
        List<String> criteria = new ArrayList<>(List.of("CLARITY", "LIMIT_CONDITION", "CONSISTENCY", "RESPECT", "HABIT"));
        if (responseType.equals("CONDITIONAL")) criteria.add("ALTERNATIVE");
        JsonNode result = ai.generate("""
                USER 발화만 평가하고 한국어로 작성한다. criteria에 있는 항목을 각각 한 번 평가하고, 0~100점과 코멘트를 쓴다.
                CLARITY는 명확성, LIMIT_CONDITION은 범위·조건 전달, CONSISTENCY는 의사 유지,
                RESPECT는 관계 존중, HABIT은 표현 습관, ALTERNATIVE는 대안 제시를 평가한다.
                사용자가 이미 실행 가능한 조건을 제시했다면 추가 대안을 말하지 않았다는 이유만으로 감점하지 않는다.
                평가와 총평은 대화에서 확인되는 표현에 근거한다. 실제 약속 이행이나 관계 상태를 단정하지 않는다.
                sentenceComparisons의 original은 USER 발화의 정확한 인용문이다.
                improved는 사용자가 밝힌 시간, 업무 범위, 대응 방향을 유지하면서 표현을 다듬는다.
                추가 업무, 일정 조정이나 다른 약속을 새로 넣지 않는다. 다듬을 표현이 없으면 sentenceComparisons는 빈 배열로 둔다.
                {summary: 문자열, items: [{criterion, score, comment}], sentenceComparisons: [{original, improved}]} 형태의 JSON을 출력한다.
                """, Map.of("goal", setup.path("goal"), "goalTag", setup.path("goalTag"), "responseType", responseType,
                "messages", modelMessages(body), "criteria", criteria),
                Map.of("summary", "화면 연동용 예시 리포트입니다.", "items", criteria.stream()
                        .map(criterion -> new Item(criterion, 80, "화면 연동용 예시 평가입니다.")).toList(),
                        "sentenceComparisons", List.of()));

        Set<String> remaining = new HashSet<>(criteria);
        List<Item> items = new ArrayList<>();
        for (JsonNode item : outputArray(result, "items")) {
            String criterion = outputText(item, "criterion");
            JsonNode score = item.path("score");
            if (!remaining.remove(criterion) || !score.isNumber() || !Double.isFinite(score.asDouble())
                    || score.asDouble() < 0 || score.asDouble() > SCORE_SCALE) throw SimulationFailure.aiError();
            items.add(new Item(criterion, score.asDouble(), outputText(item, "comment")));
        }
        if (!remaining.isEmpty()) throw SimulationFailure.aiError();

        List<Comparison> comparisons = new ArrayList<>();
        for (JsonNode pair : outputArray(result, "sentenceComparisons")) {
            String original = outputText(pair, "original");
            String improved = outputText(pair, "improved");
            if (!original.strip().equals(improved.strip())
                    && userMessages.stream().anyMatch(message -> message.contains(original))) {
                comparisons.add(new Comparison(original, improved));
            }
        }
        long total = Math.round(items.stream().mapToDouble(Item::score).average().orElseThrow());
        return new ReportResponse(total, SCORE_SCALE, outputText(result, "summary"), items, comparisons);
    }

    private static Map<String, JsonNode> replyContext(JsonNode setup) {
        JsonNode context = setup.path("conflictContext");
        return Map.of("goal", setup.path("goal"), "responseType", context.path("responseType"),
                "availableCondition", context.path("availableCondition"));
    }

    private static Map<String, Object> roleplayContext(JsonNode setup) {
        JsonNode summary = setup.path("conflictContext").path("summary");
        JsonNode contact = setup.path("contact");
        return Map.of(
                "requesterName", contact.path("name"),
                "relationCategory", contact.path("relationCategory"),
                "relationDetail", contact.path("relationDetail"),
                "personalityTag", contact.path("personalityTag"),
                "speechStyle", contact.path("speechStyle"),
                "recipientName", setup.path("userName"),
                "situationType", setup.path("situationType"),
                "requestContent", summary.path("requestContent"),
                "deadlineText", summary.path("deadlineText"),
                "currentDate", LocalDate.now(KST).format(ROLEPLAY_DATE));
    }

    private static JsonNode setup(JsonNode body) {
        JsonNode setup = body.path("setup");
        if (!setup.isObject()) throw SimulationFailure.invalid("setup은 필수입니다.");
        JsonNode context = setup.path("conflictContext");
        if (!context.isObject()) throw SimulationFailure.invalid("conflictContext는 필수입니다.");
        if (!context.path("summary").isObject() || !context.path("relatedSchedules").isArray()) {
            throw SimulationFailure.invalid("summary와 relatedSchedules는 필수입니다.");
        }
        requireChoice(context, "responseType", Set.of("ACCEPT", "CONDITIONAL", "DECLINE"));
        requireText(setup, "userName");
        requireText(setup, "goal");
        requireChoice(setup, "situationType", Set.of("GENERAL_REQUEST", "RE_PERSUASION"));
        optionalChoice(setup, "goalTag", Set.of("CLEAR_INTENT", "STATE_LIMIT", "HOLD_POSITION", "RELATIONSHIP"));
        JsonNode contact = setup.path("contact");
        requireText(contact, "name");
        requireChoice(contact, "relationCategory", Set.of("COLLEAGUE", "SENIOR", "JUNIOR", "FRIEND", "PARTNER", "FAMILY", "OTHER"));
        optionalChoice(contact, "personalityTag", Set.of("EMOTIONAL", "PUSHY", "LOGICAL", "GUILT_TRIP", "COOL"));
        return setup;
    }

    private static List<String> messages(JsonNode body, String lastSender) {
        JsonNode messages = body.path("messages");
        if (!messages.isArray() || messages.isEmpty()) throw SimulationFailure.invalid("messages는 필수입니다.");
        if (messages.size() > MAX_TURNS * 2 + 1) throw SimulationFailure.invalid("대화 내역이 턴 상한을 초과했습니다.");
        List<String> userMessages = new ArrayList<>();
        for (JsonNode message : messages) {
            requireChoice(message, "sender", Set.of("USER", "AI"));
            requireText(message, "content");
            if (message.path("content").asText().length() > MAX_MESSAGE_LENGTH) {
                throw SimulationFailure.invalid("메시지는 최대 1500자입니다.");
            }
            if (message.path("sender").asText().equals("USER")) userMessages.add(message.path("content").asText());
        }
        if (userMessages.size() > MAX_TURNS) throw SimulationFailure.invalid("사용자 메시지가 턴 상한을 초과했습니다.");
        if (lastSender != null && !lastSender.equals(messages.get(messages.size() - 1).path("sender").asText())) {
            throw SimulationFailure.invalid("마지막 메시지의 sender는 " + lastSender + "여야 합니다.");
        }
        return userMessages;
    }

    private static void requireText(JsonNode node, String field) {
        if (!hasText(node.path(field))) throw SimulationFailure.invalid(field + "은 필수입니다.");
    }

    private static List<Map<String, JsonNode>> modelMessages(JsonNode body) {
        List<Map<String, JsonNode>> result = new ArrayList<>();
        for (JsonNode message : body.path("messages")) {
            result.add(Map.of("sender", message.path("sender"), "content", message.path("content")));
        }
        return result;
    }

    private static void requireChoice(JsonNode node, String field, Set<String> choices) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || !choices.contains(value.asText())) {
            throw SimulationFailure.invalid(field + " 값이 올바르지 않습니다.");
        }
    }

    private static void optionalChoice(JsonNode node, String field, Set<String> choices) {
        JsonNode value = node.path(field);
        if (!value.isMissingNode() && !value.isNull()) requireChoice(node, field, choices);
    }

    private static boolean hasText(JsonNode value) {
        return value.isTextual() && !value.asText().isBlank();
    }

    private static String outputText(JsonNode node, String field) {
        if (node == null || !hasText(node.path(field))
                || node.path(field).asText().length() > MAX_MESSAGE_LENGTH) throw SimulationFailure.aiError();
        return node.path(field).asText();
    }

    private static List<String> suggestedReplies(JsonNode result) {
        JsonNode choices = result.path("suggestedReplies");
        if (!choices.isArray()) return List.of();
        List<String> replies = new ArrayList<>();
        for (JsonNode choice : choices) {
            if (!hasText(choice)) continue;
            String reply = choice.asText().strip();
            if (reply.length() <= 160 && !replies.contains(reply)) replies.add(reply);
            if (replies.size() == 3) break;
        }
        return replies;
    }

    private static JsonNode outputArray(JsonNode node, String field) {
        if (node == null || !node.path(field).isArray()) throw SimulationFailure.aiError();
        return node.path(field);
    }

    @ExceptionHandler(SimulationFailure.class)
    ResponseEntity<ErrorResponse> failure(SimulationFailure error) {
        return ResponseEntity.status(error.status).cacheControl(CacheControl.noStore())
                .body(new ErrorResponse(new ErrorDetail(error.code, error.getMessage())));
    }

    private JsonNode readBody(HttpServletRequest request) {
        try {
            if (request.getContentLengthLong() > MAX_BODY_BYTES) throw tooLarge();
            byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) throw tooLarge();
            JsonNode body = requestReader.readValue(bytes);
            if (body == null || !body.isObject()) throw SimulationFailure.invalid("요청 본문은 JSON 객체여야 합니다.");
            return body;
        } catch (IOException | JacksonException e) {
            throw SimulationFailure.invalid("요청 본문은 올바른 JSON이어야 합니다.");
        }
    }

    private static SimulationFailure tooLarge() {
        return new SimulationFailure(413, "PAYLOAD_TOO_LARGE", "요청 본문은 최대 96 KiB입니다.");
    }

    record StartResponse(String firstAiMessage, int maxTurns, List<String> suggestedReplies) {}
    record ChatResponse(String aiMessage, boolean shouldEnd, String endReason, List<String> suggestedReplies) {}
    record HintResponse(String direction, List<String> keywords) {}
    record Item(String criterion, double score, String comment) {}
    record Comparison(String original, String improved) {}
    record ReportResponse(long totalScore, int scoreScale, String summary, List<Item> items,
                          List<Comparison> sentenceComparisons) {}
    record ErrorDetail(String code, String message) {}
    record ErrorResponse(ErrorDetail error) {}
}
