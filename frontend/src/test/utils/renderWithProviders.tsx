import { render, type RenderOptions } from '@testing-library/react'
import { configureStore } from '@reduxjs/toolkit'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router-dom'
import type { ReactElement, ReactNode } from 'react'
import { rootReducer } from '../../app/rootReducer'
import { authApi } from '../../features/auth/authApi'
import { declarationApi } from '../../features/declaration/declarationApi'
import { trustApi } from '../../features/trust/trustApi'
import { templeApi } from '../../features/temple-profile/hooks/templeApi'
import { employeeApi } from '../../features/employee/employeeApi'
import { contractorApi } from '../../features/contractor/contractorApi'
import { documentApi } from '../../features/document/documentApi'
import { notificationApi } from '../../features/notification/notificationApi'
import { exportApi } from '../../features/export/exportApi'
import { adminApi } from '../../features/admin/adminApi'
import { dcApi } from '../../features/dc/dcApi'
import { geoApi } from '../../features/geo/geoApi'
import { accessControlApi } from '../../features/access-control/accessControlApi'
import { financeApi } from '../../features/finance/financeApi'
import { financeReportApi } from '../../features/finance-reporting/financeReportApi'
import { financeOnboardingApi } from '../../features/finance-onboarding/financeOnboardingApi'

function buildTestStore(preloadedState?: Parameters<typeof configureStore>[0]['preloadedState']) {
  return configureStore({
    reducer: rootReducer,
    preloadedState,
    middleware: (getDefaultMiddleware) =>
      getDefaultMiddleware().concat(
        authApi.middleware,
        declarationApi.middleware,
        trustApi.middleware,
        templeApi.middleware,
        employeeApi.middleware,
        contractorApi.middleware,
        documentApi.middleware,
        notificationApi.middleware,
        exportApi.middleware,
        adminApi.middleware,
        dcApi.middleware,
        geoApi.middleware,
        accessControlApi.middleware,
        financeApi.middleware,
        financeReportApi.middleware,
        financeOnboardingApi.middleware,
      ),
  })
}

interface WrapperOptions extends Omit<RenderOptions, 'wrapper'> {
  preloadedState?: Parameters<typeof buildTestStore>[0]
  initialRoute?: string
}

function Wrapper({
  children,
  store,
  initialRoute,
}: {
  children: ReactNode
  store: ReturnType<typeof buildTestStore>
  initialRoute: string
}) {
  return (
    <Provider store={store}>
      <MemoryRouter initialEntries={[initialRoute]}>
        {children}
      </MemoryRouter>
    </Provider>
  )
}

export function renderWithProviders(
  ui: ReactElement,
  { preloadedState, initialRoute = '/', ...renderOptions }: WrapperOptions = {}
) {
  const store = buildTestStore(preloadedState)
  return {
    ...render(ui, {
      wrapper: ({ children }) => (
        <Wrapper store={store} initialRoute={initialRoute}>{children}</Wrapper>
      ),
      ...renderOptions,
    }),
    store,
  }
}
