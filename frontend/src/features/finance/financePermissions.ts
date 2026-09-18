import { USER_ROLES, type UserRole } from '@/constants/roles'

/**
 * What the Source Mapper lets a role do (FIN-054B).
 *
 * **This is user experience, not security.** Every rule here is already enforced on
 * `MappingAdminServiceImpl` with `@PreAuthorize`, and a request that gets past this file is
 * refused by the server with 403. The point of having it is that a disabled button is kinder
 * than a refusal, not that it protects anything.
 *
 * Mirrors the backend constants exactly:
 * - read  — `RoleConstants.CAN_READ_FINANCE_CONFIG` (SUPER_ADMIN, DISTRICT_COLLECTOR, DC_STAFF, AUDITOR)
 * - write — `RoleConstants.CAN_ACT_DC` (SUPER_ADMIN, DISTRICT_COLLECTOR)
 *
 * `TEMPLE_AUTHORITY` is absent from both. A mapping rule decides which revenue category a
 * temple's own income is counted under, so the regulated party does not get to reclassify it
 * (FIN-054A plan §11, decision D1).
 */
export interface MappingCapabilities {
  canView: boolean
  canCreate: boolean
  canEdit: boolean
  canChangeStatus: boolean
}

const READ_ROLES: readonly UserRole[] = [
  USER_ROLES.SUPER_ADMIN,
  USER_ROLES.DISTRICT_COLLECTOR,
  USER_ROLES.DC_STAFF,
  USER_ROLES.AUDITOR,
]

const WRITE_ROLES: readonly UserRole[] = [
  USER_ROLES.SUPER_ADMIN,
  USER_ROLES.DISTRICT_COLLECTOR,
]

export function getMappingCapabilities(role: string | undefined | null): MappingCapabilities {
  const canView = !!role && READ_ROLES.includes(role as UserRole)
  const canWrite = !!role && WRITE_ROLES.includes(role as UserRole)

  return {
    canView,
    // Writing implies viewing; a role that cannot see a rule must not be offered a way to make one.
    canCreate: canView && canWrite,
    canEdit: canView && canWrite,
    canChangeStatus: canView && canWrite,
  }
}
