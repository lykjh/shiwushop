package com.shiwushop.common.media;

public interface PublicMediaReference {
    /** 判断图片是否被可公开浏览的商品、店铺、品牌或评价引用。 */
    boolean isPubliclyReferenced(Long mediaId);
}
