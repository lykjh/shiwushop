package com.shiwushop.unit.account;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.media.MediaAssetAccess;
import com.shiwushop.common.privacy.PrivacyCipher;
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
    final PrivacyCipher privacy = mock(PrivacyCipher.class);
    final UserProfileServiceImpl service = new UserProfileServiceImpl(mapper, accounts, media, privacy);

    @Test
    void readsOnlyRequestedUser() {
        var profile = new UserProfile();
        profile.setNickname("旧");
        profile.setAvatarMediaId(3L);
        when(mapper.selectById(1L)).thenReturn(profile);
        assertEquals("旧", service.get(1L).nickname());
        verify(accounts).requireActive(1L);
        assertThrows(BusinessException.class, () -> service.get(2L));
    }

    @Test
    void replacesBothFieldsAndReturnsRequestWithoutRereading() {
        when(mapper.replaceProfile(1L, "新", null)).thenReturn(1);
        var result = service.update(1L, new UserProfileUpdateDTO(" 新 ", null));
        assertEquals("新", result.nickname());
        assertNull(result.avatarMediaId());
        verify(mapper).replaceProfile(1L, "新", null);
        verifyNoInteractions(media);
        verify(mapper, never()).selectById(anyLong());
    }

    @Test
    void checksMediaBeforeWritingAndPropagatesFailure() {
        doThrow(new BusinessException(409, "未就绪")).when(media).requireReadyOwned(5L, 1L);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.update(1L, new UserProfileUpdateDTO("新", 5L))).getCode());
        verifyNoInteractions(mapper);
    }

    @Test
    void updatesEmailOnlyWhenSpecified() {
        when(mapper.replaceProfile(1L, "新", null)).thenReturn(1);
        when(privacy.encrypt(1L, "profile-email", "a@qq.com")).thenReturn(new byte[]{1});
        var save = new UserProfileUpdateDTO("新", null);
        save.setEmail("a@qq.com");
        assertEquals("a@qq.com", service.update(1L, save).email());
        verify(mapper).replaceEmail(1L, new byte[]{1});
        var clear = new UserProfileUpdateDTO("新", null);
        clear.setEmail(null);
        assertNull(service.update(1L, clear).email());
        verify(mapper).replaceEmail(1L, null);
        service.update(1L, new UserProfileUpdateDTO("新", null));
        verify(mapper, times(2)).replaceEmail(eq(1L), nullable(byte[].class));
    }
}
