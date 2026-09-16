package com.templeregistry.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} processing for the registry runtime only.
 *
 * <p>This annotation used to sit on {@code TempleRegistryApplication}. It was moved here
 * because the sync worker is the same artifact started with a different profile, and
 * {@code @EnableScheduling} is global: leaving it on the application class would start
 * every existing background job in <em>both</em> processes.
 *
 * <p>That is not a theoretical concern. {@code EmailDeliveryService.processQueue()} runs
 * every ten seconds and claims pending rows with
 * {@code findPendingBatch(50)} -- a plain {@code SELECT ... LIMIT} with no row locking,
 * followed by a save. Two processes running it concurrently would both pick up the same
 * {@code PENDING} rows and <b>deliver duplicate emails</b> to real recipients. The same
 * pattern applies to {@code NotificationRouter.dispatchPending()} (five seconds),
 * {@code EmailRetryScheduler}, {@code NoticeExpiryScheduler} and
 * {@code OverdueWorkflowScheduler}.
 *
 * <p>Gating the annotation instead of annotating each scheduler was chosen deliberately:
 * several of those beans also expose non-scheduled methods that other services call
 * (notably {@code EmailDeliveryService.enqueue}), so excluding the beans themselves from
 * the worker context would cascade through the notification subsystem. Withholding the
 * <em>scheduling infrastructure</em> leaves every bean present and injectable while
 * making its {@code @Scheduled} methods inert.
 *
 * <p>The sync worker therefore does not use {@code @Scheduled} at all. It schedules its
 * own jobs explicitly against the {@code financeSyncScheduler} {@code TaskScheduler}
 * defined in {@code SyncWorkerConfig}, which schedules exactly what it is given and
 * nothing else.
 *
 * <p>Consequence for the worker: it must never deliver email directly. It writes to
 * {@code email_outbox} and the registry runtime delivers -- which is what the outbox
 * pattern is for.
 */
@Configuration
@Profile(FinanceProfiles.REGISTRY)
@EnableScheduling
public class SchedulingConfig {
}
