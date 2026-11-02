package com.shiwushop.common.pagination;

import com.shiwushop.common.BusinessException;


public final class PageSpec {
    private final int page;
    private final int size;

    private PageSpec(int page, int size) {
        this.page = page;
        this.size = size;
    }

    /** 使用默认错误消息验证页码和每页条数。 */
    public static PageSpec of(int page, int size) {
        return of(page, size, "分页参数错误");
    }

    /** 要求页码从 1 开始、每页 1 至 100 条，并允许调用方定制错误消息。 */
    public static PageSpec of(int page, int size, String errorMessage) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(400, errorMessage);
        }
        return new PageSpec(page, size);
    }

    public int page() {
        return page;
    }

    public int size() {
        return size;
    }

    /** 计算 SQL OFFSET，使用 long 防止页码与条数相乘时 int 溢出。 */
    public long offset() {
        return ((long) page - 1) * size;
    }
}
