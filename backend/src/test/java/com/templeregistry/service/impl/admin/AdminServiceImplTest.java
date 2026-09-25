package com.templeregistry.service.impl.admin;

import com.templeregistry.dto.request.admin.CreateUserRequest;
import com.templeregistry.entity.auth.User;
import com.templeregistry.entity.auth.UserRole;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.entity.temple.TempleStatus;
import com.templeregistry.repository.auth.UserRepository;
import com.templeregistry.repository.geo.DistrictRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.audit.AuditService;
import com.templeregistry.service.temple.TempleSearchSummaryService;
import com.templeregistry.util.PaginationUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private TempleRepository templeRepository;
    @Mock private DistrictRepository districtRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TempleSearchSummaryService searchSummaryService;
    @Mock private AuditService auditService;
    @Mock private PaginationUtil paginationUtil;
    @Mock private com.templeregistry.service.notification.EmailService emailService;
    @Mock private com.templeregistry.repository.auth.RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private AdminServiceImpl adminService;

    @BeforeEach
    void setUp() {
        var claims = new ScopeHelper.Claims(99L, "SUPER_ADMIN", null, null, "admin", "EDIT");
        var auth = new UsernamePasswordAuthenticationToken(claims, null, java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createUser_ShouldLogAudit() {
        CreateUserRequest rq = CreateUserRequest.builder()
                .username("testuser").email("test@example.com").password("pass123")
                .fullName("Test User").role(UserRole.DC_STAFF).build();

        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            u.setId(1L);
            return u;
        });

        adminService.createUser(rq);

        verify(userRepository).save(any(User.class));
        verify(auditService).logDataEvent(anyLong(), eq("SUPER_ADMIN"), eq("CREATE_USER"),
                eq("User"), eq(1L), contains("testuser"));
    }

    @Test
    void should_autoCreateTemple_when_roleIsTempleAuthority_and_createTempleTrue() {
        CreateUserRequest rq = CreateUserRequest.builder()
                .username("ta_user").email("ta@example.com").password("pass1234")
                .fullName("Temple Admin").role(UserRole.TEMPLE_AUTHORITY)
                .districtId(1L).templeName("Test Temple").aadhaarNumber("123456789012")
                .createTemple(true)
                .build();

        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(templeRepository.save(any())).thenAnswer(i -> {
            Temple t = i.getArgument(0);
            t.setId(10L);
            return t;
        });
        when(userRepository.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            u.setId(2L);
            return u;
        });

        adminService.createUser(rq);

        verify(templeRepository).save(any());
        verify(userRepository).save(argThat(u -> Long.valueOf(10L).equals(u.getTempleId())));
        verify(searchSummaryService).scheduleRefresh(10L);
    }

    @Test
    void should_assignExistingTemple_when_createTempleFalse_and_templeIsActive() {
        Temple existingTemple = Temple.builder()
                .name("Ancient Temple").registrationNumber("KA-TMP-ABCD1234")
                .status(TempleStatus.ACTIVE).districtId(1L)
                .grade(com.templeregistry.entity.temple.TempleGrade.A)
                .primaryDeity("Lord Shiva")
                .build();
        existingTemple.setId(55L);

        CreateUserRequest rq = CreateUserRequest.builder()
                .username("ta_user2").email("ta2@example.com").password("pass1234")
                .fullName("Temple Admin 2").role(UserRole.TEMPLE_AUTHORITY)
                .districtId(1L).aadhaarNumber("987654321012")
                .createTemple(false).existingTempleId(55L)
                .build();

        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(templeRepository.findById(55L)).thenReturn(Optional.of(existingTemple));
        when(userRepository.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            u.setId(3L);
            return u;
        });

        adminService.createUser(rq);

        verify(templeRepository, never()).save(any());        // no new temple created
        verify(userRepository).save(argThat(u -> Long.valueOf(55L).equals(u.getTempleId())));
    }

    @Test
    void should_throwException_when_createTempleFalse_and_noExistingTempleIdProvided() {
        CreateUserRequest rq = CreateUserRequest.builder()
                .username("ta_user3").email("ta3@example.com").password("pass1234")
                .fullName("Temple Admin 3").role(UserRole.TEMPLE_AUTHORITY)
                .districtId(1L).aadhaarNumber("111122223333")
                .createTemple(false)   // existingTempleId is null
                .build();

        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        assertThatThrownBy(() -> adminService.createUser(rq))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("existing temple must be selected");
    }

    @Test
    void should_throwException_when_assigningArchivedTemple() {
        Temple archived = Temple.builder()
                .name("Old Temple").registrationNumber("KA-TMP-OLD00001")
                .status(TempleStatus.ARCHIVED).districtId(1L)
                .grade(com.templeregistry.entity.temple.TempleGrade.C)
                .primaryDeity("Unknown")
                .build();
        archived.setId(99L);

        CreateUserRequest rq = CreateUserRequest.builder()
                .username("ta_user4").email("ta4@example.com").password("pass1234")
                .fullName("Temple Admin 4").role(UserRole.TEMPLE_AUTHORITY)
                .districtId(1L).aadhaarNumber("444455556666")
                .createTemple(false).existingTempleId(99L)
                .build();

        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(templeRepository.findById(99L)).thenReturn(Optional.of(archived));

        assertThatThrownBy(() -> adminService.createUser(rq))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archived");
    }

    @Test
    void should_throwException_when_createTempleTrue_and_noTempleNameProvided() {
        CreateUserRequest rq = CreateUserRequest.builder()
                .username("ta_user5").email("ta5@example.com").password("pass1234")
                .fullName("Temple Admin 5").role(UserRole.TEMPLE_AUTHORITY)
                .districtId(1L).aadhaarNumber("777788889999")
                .createTemple(true)   // templeName is null/blank
                .build();

        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        assertThatThrownBy(() -> adminService.createUser(rq))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Temple name is required");
    }

    @Test
    void rebuildSearchSummary_ShouldLogAudit() {
        adminService.rebuildSearchSummary();
        verify(searchSummaryService).rebuildAll();
        verify(auditService).logDataEvent(anyLong(), eq("SUPER_ADMIN"), eq("REBUILD_SEARCH_SUMMARY"),
                eq("System"), eq(0L), anyString());
    }

    // ── Super Admin: reset another user's password ───────────────────────────

    private User existingUser() {
        User u = User.builder()
                .username("ta_chamundi").email("ta@example.com").fullName("Temple Admin")
                .role(UserRole.TEMPLE_AUTHORITY).passwordHash("old-hash")
                .isActive(true).build();
        u.setId(42L);
        return u;
    }

    @Test
    void should_storeHashedTemporaryPassword_when_superAdminResetsUserPassword() {
        User target = existingUser();
        when(userRepository.findById(42L)).thenReturn(Optional.of(target));
        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash-of-temp");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        adminService.resetUserPassword(42L);

        // Whatever was generated must have gone through the encoder, never stored raw.
        var generated = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(generated.capture());
        assertThat(target.getPasswordHash()).isEqualTo("bcrypt-hash-of-temp");
        assertThat(target.getPasswordHash()).isNotEqualTo(generated.getValue());
        assertThat(generated.getValue()).hasSizeGreaterThanOrEqualTo(8);
    }

    @Test
    void should_requirePasswordChange_when_superAdminResetsUserPassword() {
        User target = existingUser();
        target.setFailedLoginCount(4);
        target.setLockedUntil(java.time.LocalDateTime.now().plusMinutes(30));
        when(userRepository.findById(42L)).thenReturn(Optional.of(target));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        adminService.resetUserPassword(42L);

        assertThat(target.isMustChangePassword()).isTrue();
        assertThat(target.getPasswordUpdatedAt()).isNotNull();
        assertThat(target.getFailedLoginCount()).isZero();
        assertThat(target.getLockedUntil()).isNull();
    }

    @Test
    void should_emailTemporaryPasswordAndRevokeSessions_when_superAdminResetsUserPassword() {
        User target = existingUser();
        when(userRepository.findById(42L)).thenReturn(Optional.of(target));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        adminService.resetUserPassword(42L);

        verify(emailService).sendTemporaryPasswordEmail(
                eq("ta@example.com"), eq("Temple Admin"), eq("ta_chamundi"),
                anyString(), contains("/login"));
        verify(refreshTokenRepository).revokeAllByUserId(eq(42L), any());
        verify(auditService).logDataEvent(anyLong(), eq("SUPER_ADMIN"),
                eq("ADMIN_RESET_USER_PASSWORD"), eq("User"), eq(42L), anyString());
    }

    @Test
    void should_clearPendingResetToken_when_superAdminResetsUserPassword() {
        User target = existingUser();
        target.setPasswordResetTokenHash("pending-hash");
        target.setPasswordResetTokenExpiresAt(java.time.LocalDateTime.now().plusMinutes(10));
        when(userRepository.findById(42L)).thenReturn(Optional.of(target));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        adminService.resetUserPassword(42L);

        assertThat(target.getPasswordResetTokenHash()).isNull();
        assertThat(target.getPasswordResetTokenExpiresAt()).isNull();
    }

    @Test
    void should_throwEntityNotFound_when_resettingPasswordForUnknownUser() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminService.resetUserPassword(404L))
                .isInstanceOf(com.templeregistry.exception.EntityNotFoundException.class);
        verifyNoInteractions(emailService);
    }
}