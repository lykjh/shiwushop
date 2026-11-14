package com.shiwushop.unit.account;

import com.shiwushop.user.controller.user.AccountDeletionRequestController;
import com.shiwushop.user.dto.user.AccountProofDTO;
import com.shiwushop.user.mapper.user.AccountDeletionRequestMapper;
import com.shiwushop.user.service.impl.AccountDeletionRequestService;
import com.shiwushop.user.service.impl.AccountProofService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountDeletionRequestControllerTest {
    private final AccountDeletionRequestService requests = mock(AccountDeletionRequestService.class);
    private final AccountProofService proof = mock(AccountProofService.class);
    private final AccountDeletionRequestController controller = new AccountDeletionRequestController(requests, proof);

    @Test
    void smsProofUsesDeletionPurposeAndCurrentSessionVersion() {
        var request = mock(HttpServletRequest.class);
        var session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("userId")).thenReturn(2L);
        when(session.getAttribute("authVersion")).thenReturn(4);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        var pending = new AccountDeletionRequestMapper.Request(1L, 2L, "PENDING",
                LocalDateTime.parse("2026-11-06T10:00:00"), LocalDateTime.parse("2026-11-13T10:00:00"), null);
        when(requests.request(2L, 4)).thenReturn(pending);

        controller.sendCode(request);
        var result = controller.create(new AccountProofDTO(null, "123456"), request);

        verify(proof).sendCode(2L, "account-deletion", "127.0.0.1");
        verify(proof).verify(2L, "account-deletion", null, "123456");
        verify(requests).request(2L, 4);
        assertEquals("PENDING", result.getData().status());
    }
}
