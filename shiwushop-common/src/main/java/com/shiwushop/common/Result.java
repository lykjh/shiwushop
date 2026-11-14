package com.shiwushop.common;

import lombok.Data;

@Data
public class Result<T> {

    /** 业务响应代码；成功为 200。 */
    private Integer code;
    /** 返回给调用方的结果说明。 */
    private String message;
    /** 响应业务数据。 */
    private T data;

    public static <T> Result<T> success() {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("success");
        return result;
    }

    public static <T> Result<T> success(T data) {
        Result<T> result = success();
        result.setData(data);
        return result;
    }

    public static <T> Result<T> error(String message) {
        Result<T> result = new Result<>();
        result.setCode(500);
        result.setMessage(message);
        return result;
    }

    public static <T> Result<T> error(Integer code, String message) {
        Result<T> result = error(message);
        result.setCode(code);
        return result;
    }
}
