package kr.malsai.simulation;

final class SimulationFailure extends RuntimeException {
    final int status;
    final String code;

    SimulationFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static SimulationFailure invalid(String message) {
        return new SimulationFailure(400, "VALIDATION_ERROR", message);
    }

    static SimulationFailure aiError() {
        return new SimulationFailure(502, "AI_ERROR", "AI 응답을 처리하지 못했습니다.");
    }
}
