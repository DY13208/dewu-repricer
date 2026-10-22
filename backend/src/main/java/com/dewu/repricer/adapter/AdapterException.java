package com.dewu.repricer.adapter;

/** retryable=true 表示限流/超时/5xx 等可重试错误。 */
public class AdapterException extends RuntimeException {
    private final boolean retryable; private final String code;
    public AdapterException(String code, String msg, boolean retryable) { super(msg); this.code = code; this.retryable = retryable; }
    public boolean retryable() { return retryable; }
    public String code() { return code; }
}
