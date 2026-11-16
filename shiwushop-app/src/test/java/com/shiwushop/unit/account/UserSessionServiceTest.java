package com.shiwushop.unit.account;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.SessionUser.Identity;
import com.shiwushop.user.auth.LoginSessions;
import com.shiwushop.user.dto.user.AccountProofDTO;
import com.shiwushop.user.service.AccountProofService;
import com.shiwushop.user.service.UserSessionService;
import com.shiwushop.user.service.impl.UserSessionServiceImpl;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserSessionServiceTest {
    private final LoginSessions sessions = mock(LoginSessions.class);
    private final AccountProofService proof = mock(AccountProofService.class);
    private final UserSessionService service = new UserSessionServiceImpl(sessions, proof);
    private final Identity current = new Identity(3L, 2, "current");
    private final AccountProofDTO password = new AccountProofDTO("correct", null);

    @Test
    void listsOwnSessionsAndMarksCurrent() {
        Instant now = Instant.parse("2026-11-12T01:00:00Z");
        when(sessions.list(current)).thenReturn(List.of(
                new LoginSessions.Entry("other", now.minusSeconds(20), now, now.plusSeconds(60)),
                new LoginSessions.Entry("current", now.minusSeconds(10), now, now.plusSeconds(60))));
        var listed = service.list(current);
        assertEquals(List.of("current", "other"),
                listed.stream().map(UserSessionService.SessionView::sessionRef).toList());
        assertTrue(listed.getFirst().current());
        assertFalse(listed.getLast().current());
    }

    @Test
    void validatesProofBeforeRevokingAndPropagatesUnknownTarget() {
        var order = inOrder(proof, sessions);
        service.revoke(current, "other", password);
        order.verify(proof).verify(3L, "session-revoke", "correct", null);
        order.verify(sessions).revoke(current, "other");
        doThrow(new BusinessException(404, "会话不存在")).when(sessions).revoke(current, "unknown");
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.revoke(current, "unknown", password)).getCode());
    }

    @Test
    void rejectsCurrentAndInvalidProofWithoutDeletion() {
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.revoke(current, "current", password)).getCode());
        doThrow(new BusinessException(401, "bad proof")).when(proof).verify(3L, "session-revoke", "correct", null);
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.revokeOthers(current, password)).getCode());
        verifyNoInteractions(sessions);
    }

    @Test
    void revokeOthersIdentifiesCurrentLoginInTheAtomicOperation() {
        service.revokeOthers(current, password);
        verify(sessions).revoke(current, "");
    }

    @Test
    void storageOutageFailsClosed() {
        when(sessions.list(current)).thenThrow(new BusinessException(503, "会话服务暂不可用"));
        assertEquals(503, assertThrows(BusinessException.class, () -> service.list(current)).getCode());
    }
}
