package com.shiwushop.unit.account;

import com.shiwushop.user.controller.user.AccountDeletionRequestController;
import com.shiwushop.user.dto.user.AccountProofDTO;
import com.shiwushop.user.mapper.user.AccountDeletionRequestMapper;
import com.shiwushop.user.service.impl.AccountDeletionRequestService;
import com.shiwushop.user.service.impl.AccountProofService;
import com.shiwushop.common.auth.SessionUser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountDeletionRequestControllerTest {
    private final AccountDeletionRequestService requests = mock(AccountDeletionRequestService.class);
    private final AccountProofService proof = mock(AccountProofService.class);
    private final AccountDeletionRequestController controller = new AccountDeletionRequestController(requests, proof);

    @Test
    void smsProofUsesDeletionPurposeAndCurrentSessionVersion() {
        var request = new MockHttpServletRequest();
        SessionUser.identify(request, new SessionUser.Identity(2L, 4, "verified"));
        request.setRemoteAddr("127.0.0.1");
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
