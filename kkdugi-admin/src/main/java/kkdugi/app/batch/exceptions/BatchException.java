package kkdugi.app.batch.exceptions;

/** 배치 API의 모든 오류. HTTP 상태와 {@code batch.*} 오류 코드를 함께 가진다(코드는 {@link BatchErrors}). */
public class BatchException extends RuntimeException {
    private final int status;
    private final String code;

    public BatchException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static BatchException badRequest(String code) {
        return new BatchException(400, code);
    }

    public static BatchException unauthorized(String code) {
        return new BatchException(401, code);
    }

    public static BatchException forbidden(String code) {
        return new BatchException(403, code);
    }

    public static BatchException notFound(String code) {
        return new BatchException(404, code);
    }

    public static BatchException conflict(String code) {
        return new BatchException(409, code);
    }

    public static BatchException gone(String code) {
        return new BatchException(410, code);
    }

    public static BatchException preconditionFailed(String code) {
        return new BatchException(412, code);
    }

    public static BatchException tooLarge(String code) {
        return new BatchException(413, code);
    }

    public static BatchException preconditionRequired(String code) {
        return new BatchException(428, code);
    }
}
