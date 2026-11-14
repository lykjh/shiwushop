package com.shiwushop.merchant.mapper.staff;

import com.shiwushop.merchant.entity.staff.ShopStaffGrant;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface ShopStaffGrantMapper {
    @Select("SELECT * FROM shop_staff_grants WHERE shop_id = #{shopId} AND user_id = #{userId} FOR UPDATE")
    ShopStaffGrant selectForUpdate(@Param("shopId") Long shopId, @Param("userId") Long userId);

    @Select("SELECT * FROM shop_staff_grants WHERE shop_id = #{shopId} AND user_id = #{userId}")
    ShopStaffGrant select(@Param("shopId") Long shopId, @Param("userId") Long userId);

    @Select("SELECT * FROM shop_staff_grants WHERE shop_id = #{shopId} ORDER BY user_id")
    List<ShopStaffGrant> list(@Param("shopId") Long shopId);

    @Insert("INSERT INTO shop_staff_grants (shop_id,user_id,can_catalog,can_inventory,can_fulfillment,status,version,granted_by_user_id) " +
            "VALUES (#{shopId},#{userId},#{canCatalog},#{canInventory},#{canFulfillment},'ACTIVE',0,#{grantedByUserId})")
    int insert(ShopStaffGrant grant);

    @Update("UPDATE shop_staff_grants SET can_catalog=#{canCatalog},can_inventory=#{canInventory}," +
            "can_fulfillment=#{canFulfillment},status='ACTIVE',version=version+1,granted_by_user_id=#{grantedByUserId}," +
            "granted_at=CURRENT_TIMESTAMP(3),revoked_by_user_id=NULL,revoked_at=NULL " +
            "WHERE shop_id=#{shopId} AND user_id=#{userId}")
    int grant(ShopStaffGrant grant);

    @Update("UPDATE shop_staff_grants SET status='REVOKED',version=version+1,revoked_by_user_id=#{actorId}," +
            "revoked_at=CURRENT_TIMESTAMP(3) WHERE shop_id=#{shopId} AND user_id=#{userId} AND status='ACTIVE'")
    int revoke(@Param("shopId") Long shopId, @Param("userId") Long userId, @Param("actorId") Long actorId);

    @Update("UPDATE shop_staff_grants SET status='REVOKED',version=version+1,revoked_by_user_id=#{actorId}," +
            "revoked_at=CURRENT_TIMESTAMP(3) WHERE shop_id=#{shopId} AND status='ACTIVE'")
    int revokeShop(@Param("shopId") Long shopId, @Param("actorId") Long actorId);
}
