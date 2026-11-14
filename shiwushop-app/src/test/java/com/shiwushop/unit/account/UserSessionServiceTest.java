package com.shiwushop.unit.account;

import com.shiwushop.common.BusinessException;
import com.shiwushop.user.dto.user.AccountProofDTO;
import com.shiwushop.user.service.impl.AccountProofService;
import com.shiwushop.user.service.impl.UserSessionService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserSessionServiceTest {
    @SuppressWarnings("unchecked")
    private final FindByIndexNameSessionRepository<MapSession> repository = mock(FindByIndexNameSessionRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<FindByIndexNameSessionRepository<? extends org.springframework.session.Session>> provider =
            mock(ObjectProvider.class);
    private final AccountProofService proof = mock(AccountProofService.class);
    private final UserSessionService service = new UserSessionService(provider, proof);
    private final HttpSession current = mock(HttpSession.class);
    private final AccountProofDTO password = new AccountProofDTO("correct", null);

    @Test
    void listsOnlyIndexedOwnSessionsWithoutRawId() {
        doReturn(repository).when(provider).getIfAvailable();
        MapSession mine = session("raw-current-id", "safe-current");
        MapSession other = session("raw-other-id", "safe-other");
        when(current.getId()).thenReturn("raw-current-id");
        when(repository.findByPrincipalName("3")).thenReturn(Map.of(mine.getId(), mine, other.getId(), other));
        var listed = service.list(3L, current);
        assertEquals(2, listed.size());
        assertTrue(listed.stream().anyMatch(s -> s.sessionRef().equals("safe-current") && s.current()));
        assertTrue(listed.stream().noneMatch(s -> s.sessionRef().contains("raw-")));
    }

    @Test
    void revokeOnlyOwnNonCurrentReferenceAfterProof() {
        doReturn(repository).when(provider).getIfAvailable();
        MapSession other = session("raw-other-id", "safe-other");
        when(current.getId()).thenReturn("raw-current-id");
        when(repository.findByPrincipalName("3")).thenReturn(Map.of(other.getId(), other));
        service.revoke(3L, current, "safe-other", password);
        verify(proof).verify(3L, "session-revoke", "correct", null);
        verify(repository).deleteById("raw-other-id");
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.revoke(3L, current, "someone-else", password)).getCode());
    }

    @Test
    void rejectsCurrentAndInvalidProofWithoutDeletion() {
        doReturn(repository).when(provider).getIfAvailable();
        MapSession mine = session("raw-current-id", "safe-current");
        when(current.getId()).thenReturn("raw-current-id");
        when(repository.findByPrincipalName("3")).thenReturn(Map.of(mine.getId(), mine));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.revoke(3L, current, "safe-current", password)).getCode());
        doThrow(new BusinessException(401, "bad proof")).when(proof).verify(3L, "session-revoke", "correct", null);
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.revokeOthers(3L, current, password)).getCode());
        verify(repository, never()).deleteById(anyString());
    }

    @Test
    void revokeOthersPreservesCurrent() {
        doReturn(repository).when(provider).getIfAvailable();
        MapSession mine = session("raw-current-id", "safe-current");
        MapSession other = session("raw-other-id", "safe-other");
        when(current.getId()).thenReturn("raw-current-id");
        when(repository.findByPrincipalName("3")).thenReturn(Map.of(mine.getId(), mine, other.getId(), other));
        service.revokeOthers(3L, current, password);
        verify(repository).deleteById("raw-other-id");
        verify(repository, never()).deleteById("raw-current-id");
    }

    @Test
    void missingIndexedRepositoryFailsClosed() {
        assertEquals(503, assertThrows(BusinessException.class,
                () -> service.list(3L, current)).getCode());
    }

    private static MapSession session(String id, String ref) {
        MapSession session = new MapSession(id);
        session.setAttribute("sessionRef", ref);
        return session;
    }
}
