import { createApi } from '@reduxjs/toolkit/query/react'
import { baseQueryWithReauth } from '../../services/baseQueryWithReauth'
import type {
  LoginRequest,
  MfaVerifyRequest,
  AadhaarOtpRequest,
  AadhaarOtpVerifyRequest,
  RegisterRequest,
  PasswordResetRequest,
  PasswordResetConfirmRequest,
  ChangePasswordRequest,
  AuthTokenResponse,
  MfaChallengeResponse,
  AadhaarOtpResponse,
  CurrentUser,
} from './authTypes'
import type { ApiResponse } from '@/types'

export const authApi = createApi({
  reducerPath: 'authApi',
  baseQuery: baseQueryWithReauth,
  endpoints: (builder) => ({
    login: builder.mutation<ApiResponse<AuthTokenResponse | MfaChallengeResponse>, LoginRequest>({
      query: (body) => ({ url: '/auth/login', method: 'POST', body }),
    }),

    mfaVerify: builder.mutation<ApiResponse<AuthTokenResponse>, MfaVerifyRequest>({
      query: (body) => ({ url: '/auth/mfa-verify', method: 'POST', body }),
    }),

    logout: builder.mutation<ApiResponse<void>, void>({
      query: () => ({ url: '/auth/logout', method: 'POST' }),
    }),

    passwordResetRequest: builder.mutation<ApiResponse<void>, PasswordResetRequest>({
      query: (body) => ({ url: '/auth/password-reset-req', method: 'POST', body }),
    }),

    passwordResetConfirm: builder.mutation<ApiResponse<void>, PasswordResetConfirmRequest>({
      query: (body) => ({ url: '/auth/password-reset', method: 'POST', body }),
    }),

    aadhaarOtpRequest: builder.mutation<ApiResponse<AadhaarOtpResponse>, AadhaarOtpRequest>({
      query: (body) => ({ url: '/auth/aadhaar-otp-req', method: 'POST', body }),
    }),

    aadhaarOtpVerify: builder.mutation<ApiResponse<AadhaarOtpResponse>, AadhaarOtpVerifyRequest>({
      query: (body) => ({ url: '/auth/aadhaar-otp-verify', method: 'POST', body }),
    }),

    register: builder.mutation<ApiResponse<void>, RegisterRequest>({
      query: (body) => ({ url: '/auth/register', method: 'POST', body }),
    }),

    getCurrentUser: builder.query<ApiResponse<CurrentUser>, void>({
      query: () => '/auth/me',
      providesTags: ['CurrentUser'],
    }),

    /**
     * Change the signed-in user's own password. Lives under /profile (not /auth) because
     * /api/v1/auth/** is permitAll on the backend for the login and reset flows.
     */
    changePassword: builder.mutation<ApiResponse<void>, ChangePasswordRequest>({
      query: (body) => ({ url: '/profile/password', method: 'PATCH', body }),
      // The forced-change flag and passwordUpdatedAt both change on success.
      invalidatesTags: ['CurrentUser'],
    }),
  }),
  tagTypes: ['CurrentUser'],
})

export const {
  useLoginMutation,
  useMfaVerifyMutation,
  useLogoutMutation,
  usePasswordResetRequestMutation,
  usePasswordResetConfirmMutation,
  useAadhaarOtpRequestMutation,
  useAadhaarOtpVerifyMutation,
  useRegisterMutation,
  useGetCurrentUserQuery,
  useChangePasswordMutation,
} = authApi
