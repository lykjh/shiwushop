package com.shiwushop.unit.account;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.media.MediaAssetAccess;
import com.shiwushop.user.dto.user.UserProfileUpdateDTO;
import com.shiwushop.user.entity.user.UserProfile;
import com.shiwushop.user.mapper.user.UserProfileMapper;
import com.shiwushop.user.service.AccountAccess;
import com.shiwushop.user.service.impl.UserProfileServiceImpl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserProfileServiceImplTest {
    final UserProfileMapper mapper = mock(UserProfileMapper.class);
    final AccountAccess accounts = mock(AccountAccess.class);
    final MediaAssetAccess media = mock(MediaAssetAccess.class);
    final UserProfileServiceImpl service = new UserProfileServiceImpl(mapper, accounts, media);
    @Test void readsOnlyRequestedUser() {
        var profile = new UserProfile(); profile.setNickname("旧"); profile.setAvatarMediaId(3L);
        when(mapper.selectById(1L)).thenReturn(profile);
        assertEquals("旧", service.get(1L).nickname());
        verify(accounts).requireActive(1L);
        assertThrows(BusinessException.class, () -> service.get(2L));
    }
    @Test void replacesBothFieldsAndReturnsRequestWithoutRereading() {
        when(mapper.replaceProfile(1L, "新", null)).thenReturn(1);
        var result = service.update(1L, new UserProfileUpdateDTO(" 新 ", null));
        assertEquals("新", result.nickname()); assertNull(result.avatarMediaId());
        verify(mapper).replaceProfile(1L, "新", null); verifyNoInteractions(media);
        verify(mapper, never()).selectById(anyLong());
    }
    @Test void checksMediaBeforeWritingAndPropagatesFailure() {
        doThrow(new BusinessException(409, "未就绪")).when(media).requireReadyOwned(5L, 1L);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.update(1L, new UserProfileUpdateDTO("新", 5L))).getCode());
        verifyNoInteractions(mapper);
    }
}
