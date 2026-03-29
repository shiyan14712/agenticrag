package com.yoswell.agenticrag.common;

/**
 * 通用接口响应 DTO。
 *
 * @param <T> 业务数据类型
 */
public class ApiResponse<T> {

    /** 业务状态码。 */
    private String code;

    /** 响应描述信息。 */
    private String message;

    /** 响应业务数据。 */
    private T data;

    /** 无参构造函数，供序列化框架使用。 */
    public ApiResponse() {}

    /**
     * 全参构造函数。
     *
     * @param code 业务状态码
     * @param message 响应描述信息
     * @param data 业务数据
     */
    public ApiResponse(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 构建成功响应。
     *
     * @param data 业务数据
     * @return 标准成功响应
     * @param <T> 业务数据类型
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("200", "Success", data);
    }

    /**
     * 构建失败响应。
     *
     * @param code 业务状态码
     * @param message 错误信息
     * @return 标准失败响应
     * @param <T> 业务数据类型
     */
    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
