package com.templeregistry.service.admin;

import com.templeregistry.common.PaginatedResponse;
import com.templeregistry.dto.request.admin.CreateUserRequest;
import com.templeregistry.dto.request.admin.UpdateUserRequest;
import com.templeregistry.dto.response.admin.TempleOptionResponse;
import com.templeregistry.dto.response.admin.UserAdminResponse;

public interface AdminService {

    PaginatedResponse<UserAdminResponse> listUsers(int page, int size, String search, String role);

    UserAdminResponse getUserById(Long id);

    UserAdminResponse createUser(CreateUserRequest request);

    UserAdminResponse updateUser(Long id, UpdateUserRequest request);

    void deactivateUser(Long id);

    void activateUser(Long id);

    /**
     * Super Admin action: replace a user's password with a freshly generated temporary one,
     * email it to the user's registered address and require them to change it at next login.
     *
     * <p>The generated password is never returned, logged or stored in plaintext — the email
     * channel is the only place it appears.
     */
    void resetUserPassword(Long id);

    void rebuildSearchSummary();

    void refreshTempleSearchSummary(Long templeId);

    /**
     * Search active temples for the "assign existing temple" dropdown.
     * Searches by name, registration number, or district name.
     */
    PaginatedResponse<TempleOptionResponse> searchTemples(String query, int page, int size);
}
