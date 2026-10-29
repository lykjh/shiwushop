package com.shiwushop.common.pagination;

import com.shiwushop.common.BusinessException;


public final class PageSpec {
    private final int page;
    private final int size;

    private PageSpec(int page, int size) {
        this.page = page;
        this.size = size;
    }

    public static PageSpec of(int page, int size) {
        return of(page, size, "分页参数错误");
    }

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

    public long offset() {
        return ((long) page - 1) * size;
    }
}
