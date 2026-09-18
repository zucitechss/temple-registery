import { describe, expect, it } from 'vitest'
import { getMappingCapabilities } from '../financePermissions'

/**
 * The frontend matrix must agree with the backend's, because a button that is offered and then
 * refused is worse than no button. These assertions are written against the backend constants:
 * CAN_READ_FINANCE_CONFIG (SUPER_ADMIN, DISTRICT_COLLECTOR, DC_STAFF, AUDITOR) and
 * CAN_ACT_DC (SUPER_ADMIN, DISTRICT_COLLECTOR).
 */
describe('getMappingCapabilities', () => {
  it.each(['SUPER_ADMIN', 'DISTRICT_COLLECTOR'])(
    'lets %s read and write, matching CAN_ACT_DC',
    (role) => {
      const can = getMappingCapabilities(role)
      expect(can).toEqual({
        canView: true,
        canCreate: true,
        canEdit: true,
        canChangeStatus: true,
      })
    },
  )

  it.each(['DC_STAFF', 'AUDITOR'])('lets %s read but never write', (role) => {
    const can = getMappingCapabilities(role)
    expect(can.canView).toBe(true)
    expect(can.canCreate).toBe(false)
    expect(can.canEdit).toBe(false)
    expect(can.canChangeStatus).toBe(false)
  })

  it('gives TEMPLE_AUTHORITY nothing — the regulated party does not reclassify its own revenue', () => {
    expect(getMappingCapabilities('TEMPLE_AUTHORITY')).toEqual({
      canView: false,
      canCreate: false,
      canEdit: false,
      canChangeStatus: false,
    })
  })

  it('gives VIEWER nothing — mapping configuration is not published information', () => {
    expect(getMappingCapabilities('VIEWER').canView).toBe(false)
  })

  it.each([undefined, null, '', 'NOT_A_ROLE'])(
    'denies everything for an unresolved role (%s)',
    (role) => {
      const can = getMappingCapabilities(role as string | undefined | null)
      expect(Object.values(can).every((v) => v === false)).toBe(true)
    },
  )

  it('never grants write without read', () => {
    const roles = [
      'SUPER_ADMIN', 'DISTRICT_COLLECTOR', 'DC_STAFF', 'AUDITOR',
      'TEMPLE_AUTHORITY', 'VIEWER', 'UNKNOWN',
    ]
    roles.forEach((role) => {
      const can = getMappingCapabilities(role)
      if (can.canCreate || can.canEdit || can.canChangeStatus) {
        expect(can.canView).toBe(true)
      }
    })
  })
})
