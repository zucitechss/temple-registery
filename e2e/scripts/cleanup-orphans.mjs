// One-off cleanup: delete known orphan workflow_instances rows that reference
// deleted temples. These were leftover from prior test runs before the cleanup
// machinery was hardened. Safe to re-run.
import mysql from 'mysql2/promise';
import 'dotenv/config';

const conn = await mysql.createConnection({
  // Host comes from the environment; the default is local, never a shared cluster (C-4).
  host: process.env.DB_HOST || 'localhost',
  port: Number(process.env.DB_PORT || '3306'),
  user: process.env.DB_USER,
  password: process.env.DB_PASSWORD,
  database: process.env.DB_NAME || 'test',
  ssl: { minVersion: 'TLSv1.2', rejectUnauthorized: false },
});

const [orphans] = await conn.query(`
  SELECT id FROM workflow_instances
  WHERE temple_id IS NOT NULL
    AND temple_id NOT IN (SELECT id FROM temples)
`);
const ids = orphans.map((r) => r.id);
console.log('Orphan workflow_instances:', ids);

if (ids.length === 0) {
  console.log('Nothing to clean.');
  await conn.end();
  process.exit(0);
}

const placeholders = ids.map(() => '?').join(',');
const childTables = [
  'entity_versions',
  'workflow_idempotency_records',
  'in_app_notifications',
  'notification_outbox',
  'governance_action_history',
  'workflow_transitions',
];

for (const table of childTables) {
  try {
    const [res] = await conn.query(
      `DELETE FROM ${table} WHERE workflow_instance_id IN (${placeholders})`,
      ids,
    );
    console.log(`  ${table}: deleted ${res.affectedRows}`);
  } catch (e) {
    console.warn(`  ${table}: ${e.message}`);
  }
}

const [wfRes] = await conn.query(
  `DELETE FROM workflow_instances WHERE id IN (${placeholders})`,
  ids,
);
console.log(`workflow_instances: deleted ${wfRes.affectedRows}`);

// Also clean orphan asset_declarations and trusts whose temple has been deleted.
const [declRes] = await conn.query(`
  DELETE FROM asset_declarations
  WHERE temple_id NOT IN (SELECT id FROM temples)
`);
console.log(`asset_declarations orphans deleted: ${declRes.affectedRows}`);

const [trustRes] = await conn.query(`
  DELETE FROM trusts
  WHERE temple_id NOT IN (SELECT id FROM temples)
`);
console.log(`trusts orphans deleted: ${trustRes.affectedRows}`);

await conn.end();
console.log('Done.');
