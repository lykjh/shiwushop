package com.shiwushop.common;

import lombok.Getter;


@Getter
public class BusinessException extends RuntimeException{
    private final int code;
    private final transient Object data;

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.data = null;
    }

    public BusinessException(int code, String message) {
        this(code, message, null);
    }

    public BusinessException(int code, String message, Object data, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.data = data;
    }
}
