package com.shiwushop.user.mapper.user;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface AccountDeletionRequestMapper {
    record Request(Long id, Long userId, String status, LocalDateTime requestedAt,
                   LocalDateTime deadlineAt, LocalDateTime withdrawnAt) {
    }

    @Select("SELECT id,user_id,status,requested_at,deadline_at,withdrawn_at " +
            "FROM account_deletion_requests WHERE user_id=#{userId} ORDER BY id DESC LIMIT 1")
    Request latest(@Param("userId") Long userId);

    @Select("SELECT id,user_id,status,requested_at,deadline_at,withdrawn_at " +
            "FROM account_deletion_requests WHERE user_id=#{userId} AND status='PENDING' LIMIT 1")
    Request pending(@Param("userId") Long userId);

    @Insert("INSERT INTO account_deletion_requests(user_id,status,requested_at,deadline_at) " +
            "VALUES(#{userId},'PENDING',#{requestedAt},#{deadlineAt})")
    void request(@Param("userId") Long userId, @Param("requestedAt") LocalDateTime requestedAt,
                 @Param("deadlineAt") LocalDateTime deadlineAt);

    @Update("UPDATE account_deletion_requests SET status='WITHDRAWN',withdrawn_at=#{now} " +
            "WHERE id=#{id} AND status='PENDING'")
    int withdraw(@Param("id") Long id, @Param("now") LocalDateTime now);
}
