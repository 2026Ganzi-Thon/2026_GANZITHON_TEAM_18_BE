package kr.malsai.conflict;

final class ConflictFailure extends RuntimeException {
    final int status;
    final String code;

    ConflictFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static ConflictFailure invalid(String message) {
        return new ConflictFailure(400, "VALIDATION_ERROR", message);
    }

    static ConflictFailure aiError() {
        return new ConflictFailure(502, "AI_ERROR", "AI 응답을 처리하지 못했습니다.");
    }
}
