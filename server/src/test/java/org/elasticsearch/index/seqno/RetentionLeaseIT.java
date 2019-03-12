/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.seqno;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.replication.ReplicationResponse;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST)
public class RetentionLeaseIT extends ESIntegTestCase  {

    public static final class RetentionLeaseSyncIntervalSettingPlugin extends Plugin {

        @Override
        public List<Setting<?>> getSettings() {
            return Collections.singletonList(IndexService.RETENTION_LEASE_SYNC_INTERVAL_SETTING);
        }

    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Stream.concat(
                super.nodePlugins().stream(),
                Stream.of(RetentionLeaseSyncIntervalSettingPlugin.class, MockTransportService.TestPlugin.class))
                .collect(Collectors.toList());
    }

    public void testRetentionLeasesSyncedOnAdd() throws Exception {
        final int numberOfReplicas = 2 - scaledRandomIntBetween(0, 2);
        internalCluster().ensureAtLeastNumDataNodes(1 + numberOfReplicas);
        final Settings settings = Settings.builder()
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", numberOfReplicas)
                .put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), true)
                .build();
        createIndex("index", settings);
        ensureGreen("index");
        final String primaryShardNodeId = clusterService().state().routingTable().index("index").shard(0).primaryShard().currentNodeId();
        final String primaryShardNodeName = clusterService().state().nodes().get(primaryShardNodeId).getName();
        final IndexShard primary = internalCluster()
                .getInstance(IndicesService.class, primaryShardNodeName)
                .getShardOrNull(new ShardId(resolveIndex("index"), 0));
        // we will add multiple retention leases and expect to see them synced to all replicas
        final int length = randomIntBetween(1, 8);
        final Map<String, RetentionLease> currentRetentionLeases = new LinkedHashMap<>();
        for (int i = 0; i < length; i++) {
            final String id = randomValueOtherThanMany(currentRetentionLeases.keySet()::contains, () -> randomAlphaOfLength(8));
            final long retainingSequenceNumber = randomLongBetween(0, Long.MAX_VALUE);
            final String source = randomAlphaOfLength(8);
            final CountDownLatch latch = new CountDownLatch(1);
            final ActionListener<ReplicationResponse> listener = ActionListener.wrap(r -> latch.countDown(), e -> fail(e.toString()));
            // simulate a peer recovery which locks the soft deletes policy on the primary
            final Closeable retentionLock = randomBoolean() ? primary.acquireRetentionLock() : () -> {};
            currentRetentionLeases.put(id, primary.addRetentionLease(id, retainingSequenceNumber, source, listener));
            latch.await();
            retentionLock.close();

            // check retention leases have been written on the primary
            assertThat(currentRetentionLeases, equalTo(RetentionLeases.toMap(primary.loadRetentionLeases())));

            // check current retention leases have been synced to all replicas
            for (final ShardRouting replicaShard : clusterService().state().routingTable().index("index").shard(0).replicaShards()) {
                final String replicaShardNodeId = replicaShard.currentNodeId();
                final String replicaShardNodeName = clusterService().state().nodes().get(replicaShardNodeId).getName();
                final IndexShard replica = internalCluster()
                        .getInstance(IndicesService.class, replicaShardNodeName)
                        .getShardOrNull(new ShardId(resolveIndex("index"), 0));
                final Map<String, RetentionLease> retentionLeasesOnReplica = RetentionLeases.toMap(replica.getRetentionLeases());
                assertThat(retentionLeasesOnReplica, equalTo(currentRetentionLeases));

                // check retention leases have been written on the replica
                assertThat(currentRetentionLeases, equalTo(RetentionLeases.toMap(replica.loadRetentionLeases())));
            }
        }
    }

    public void testRetentionLeaseSyncedOnRemove() throws Exception {
        final int numberOfReplicas = 2 - scaledRandomIntBetween(0, 2);
        internalCluster().ensureAtLeastNumDataNodes(1 + numberOfReplicas);
        final Settings settings = Settings.builder()
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", numberOfReplicas)
                .put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), true)
                .build();
        createIndex("index", settings);
        ensureGreen("index");
        final String primaryShardNodeId = clusterService().state().routingTable().index("index").shard(0).primaryShard().currentNodeId();
        final String primaryShardNodeName = clusterService().state().nodes().get(primaryShardNodeId).getName();
        final IndexShard primary = internalCluster()
                .getInstance(IndicesService.class, primaryShardNodeName)
                .getShardOrNull(new ShardId(resolveIndex("index"), 0));
        final int length = randomIntBetween(1, 8);
        final Map<String, RetentionLease> currentRetentionLeases = new LinkedHashMap<>();
        for (int i = 0; i < length; i++) {
            final String id = randomValueOtherThanMany(currentRetentionLeases.keySet()::contains, () -> randomAlphaOfLength(8));
            final long retainingSequenceNumber = randomLongBetween(0, Long.MAX_VALUE);
            final String source = randomAlphaOfLength(8);
            final CountDownLatch latch = new CountDownLatch(1);
            final ActionListener<ReplicationResponse> listener = ActionListener.wrap(r -> latch.countDown(), e -> fail(e.toString()));
            // simulate a peer recovery which locks the soft deletes policy on the primary
            final Closeable retentionLock = randomBoolean() ? primary.acquireRetentionLock() : () -> {};
            currentRetentionLeases.put(id, primary.addRetentionLease(id, retainingSequenceNumber, source, listener));
            latch.await();
            retentionLock.close();
        }

        for (int i = 0; i < length; i++) {
            final String id = randomFrom(currentRetentionLeases.keySet());
            final CountDownLatch latch = new CountDownLatch(1);
            primary.removeRetentionLease(id, ActionListener.wrap(r -> latch.countDown(), e -> fail(e.toString())));
            // simulate a peer recovery which locks the soft deletes policy on the primary
            final Closeable retentionLock = randomBoolean() ? primary.acquireRetentionLock() : () -> {};
            currentRetentionLeases.remove(id);
            latch.await();
            retentionLock.close();

            // check retention leases have been written on the primary
            assertThat(currentRetentionLeases, equalTo(RetentionLeases.toMap(primary.loadRetentionLeases())));

            // check current retention leases have been synced to all replicas
            for (final ShardRouting replicaShard : clusterService().state().routingTable().index("index").shard(0).replicaShards()) {
                final String replicaShardNodeId = replicaShard.currentNodeId();
                final String replicaShardNodeName = clusterService().state().nodes().get(replicaShardNodeId).getName();
                final IndexShard replica = internalCluster()
                        .getInstance(IndicesService.class, replicaShardNodeName)
                        .getShardOrNull(new ShardId(resolveIndex("index"), 0));
                final Map<String, RetentionLease> retentionLeasesOnReplica = RetentionLeases.toMap(replica.getRetentionLeases());
                assertThat(retentionLeasesOnReplica, equalTo(currentRetentionLeases));

                // check retention leases have been written on the replica
                assertThat(currentRetentionLeases, equalTo(RetentionLeases.toMap(replica.loadRetentionLeases())));
            }
        }
    }

    public void testRetentionLeasesSyncOnExpiration() throws Exception {
        final int numberOfReplicas = 2 - scaledRandomIntBetween(0, 2);
        internalCluster().ensureAtLeastNumDataNodes(1 + numberOfReplicas);
        final long estimatedTimeIntervalMillis = ThreadPool.ESTIMATED_TIME_INTERVAL_SETTING.get(Settings.EMPTY).millis();
        final TimeValue retentionLeaseTimeToLive =
                TimeValue.timeValueMillis(randomLongBetween(estimatedTimeIntervalMillis, 2 * estimatedTimeIntervalMillis));
        final Settings settings = Settings.builder()
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", numberOfReplicas)
                .put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), true)
                .put(IndexService.RETENTION_LEASE_SYNC_INTERVAL_SETTING.getKey(), TimeValue.timeValueSeconds(1))
                .build();
        createIndex("index", settings);
        ensureGreen("index");
        final String primaryShardNodeId = clusterService().state().routingTable().index("index").shard(0).primaryShard().currentNodeId();
        final String primaryShardNodeName = clusterService().state().nodes().get(primaryShardNodeId).getName();
        final IndexShard primary = internalCluster()
                .getInstance(IndicesService.class, primaryShardNodeName)
                .getShardOrNull(new ShardId(resolveIndex("index"), 0));
        // we will add multiple retention leases, wait for some to expire, and assert a consistent view between the primary and the replicas
        final int length = randomIntBetween(1, 8);
        for (int i = 0; i < length; i++) {
            // update the index for retention leases to live a long time
            final AcknowledgedResponse longTtlResponse = client().admin()
                    .indices()
                    .prepareUpdateSettings("index")
                    .setSettings(
                            Settings.builder()
                                    .putNull(IndexSettings.INDEX_SOFT_DELETES_RETENTION_LEASE_PERIOD_SETTING.getKey())
                                    .build())
                    .get();
            assertTrue(longTtlResponse.isAcknowledged());

            final String id = randomAlphaOfLength(8);
            final long retainingSequenceNumber = randomLongBetween(0, Long.MAX_VALUE);
            final String source = randomAlphaOfLength(8);
            final CountDownLatch latch = new CountDownLatch(1);
            final ActionListener<ReplicationResponse> listener = ActionListener.wrap(r -> latch.countDown(), e -> fail(e.toString()));
            final RetentionLease currentRetentionLease = primary.addRetentionLease(id, retainingSequenceNumber, source, listener);
            final long now = System.nanoTime();
            latch.await();

            // check current retention leases have been synced to all replicas
            for (final ShardRouting replicaShard : clusterService().state().routingTable().index("index").shard(0).replicaShards()) {
                final String replicaShardNodeId = replicaShard.currentNodeId();
                final String replicaShardNodeName = clusterService().state().nodes().get(replicaShardNodeId).getName();
                final IndexShard replica = internalCluster()
                        .getInstance(IndicesService.class, replicaShardNodeName)
                        .getShardOrNull(new ShardId(resolveIndex("index"), 0));
                assertThat(replica.getRetentionLeases().leases(), anyOf(empty(), contains(currentRetentionLease)));
            }

            // update the index for retention leases to short a long time, to force expiration
            final AcknowledgedResponse shortTtlResponse = client().admin()
                    .indices()
                    .prepareUpdateSettings("index")
                    .setSettings(
                            Settings.builder()
                                    .put(IndexSettings.INDEX_SOFT_DELETES_RETENTION_LEASE_PERIOD_SETTING.getKey(), retentionLeaseTimeToLive)
                                    .build())
                    .get();
            assertTrue(shortTtlResponse.isAcknowledged());

            // sleep long enough that the current retention lease has expired
            final long later = System.nanoTime();
            Thread.sleep(Math.max(0, retentionLeaseTimeToLive.millis() - TimeUnit.NANOSECONDS.toMillis(later - now)));
            assertBusy(() -> assertThat(primary.getRetentionLeases().leases(), empty()));

            // now that all retention leases are expired should have been synced to all replicas
            assertBusy(() -> {
                for (final ShardRouting replicaShard : clusterService().state().routingTable().index("index").shard(0).replicaShards()) {
                    final String replicaShardNodeId = replicaShard.currentNodeId();
                    final String replicaShardNodeName = clusterService().state().nodes().get(replicaShardNodeId).getName();
                    final IndexShard replica = internalCluster()
                            .getInstance(IndicesService.class, replicaShardNodeName)
                            .getShardOrNull(new ShardId(resolveIndex("index"), 0));
                    assertThat(replica.getRetentionLeases().leases(), empty());
                }
            });
        }
    }

    public void testBackgroundRetentionLeaseSync() throws Exception {
        final int numberOfReplicas = 2 - scaledRandomIntBetween(0, 2);
        internalCluster().ensureAtLeastNumDataNodes(1 + numberOfReplicas);
        final Settings settings = Settings.builder()
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", numberOfReplicas)
                .put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), true)
                .put(IndexService.RETENTION_LEASE_SYNC_INTERVAL_SETTING.getKey(), TimeValue.timeValueSeconds(1))
                .build();
        createIndex("index", settings);
        ensureGreen("index");
        final String primaryShardNodeId = clusterService().state().routingTable().index("index").shard(0).primaryShard().currentNodeId();
        final String primaryShardNodeName = clusterService().state().nodes().get(primaryShardNodeId).getName();
        final IndexShard primary = internalCluster()
                .getInstance(IndicesService.class, primaryShardNodeName)
                .getShardOrNull(new ShardId(resolveIndex("index"), 0));
        // we will add multiple retention leases and expect to see them synced to all replicas
        final int length = randomIntBetween(1, 8);
        final Map<String, RetentionLease> currentRetentionLeases = new LinkedHashMap<>(length);
        final List<String> ids = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            final String id = randomValueOtherThanMany(currentRetentionLeases.keySet()::contains, () -> randomAlphaOfLength(8));
            ids.add(id);
            final long retainingSequenceNumber = randomLongBetween(0, Long.MAX_VALUE);
            final String source = randomAlphaOfLength(8);
            final CountDownLatch latch = new CountDownLatch(1);
            // put a new lease
            currentRetentionLeases.put(
                    id,
                    primary.addRetentionLease(id, retainingSequenceNumber, source, ActionListener.wrap(latch::countDown)));
            latch.await();
            // now renew all existing leases; we expect to see these synced to the replicas
            for (int j = 0; j <= i; j++) {
                currentRetentionLeases.put(
                        ids.get(j),
                        primary.renewRetentionLease(
                                ids.get(j),
                                randomLongBetween(currentRetentionLeases.get(ids.get(j)).retainingSequenceNumber(), Long.MAX_VALUE),
                                source));
            }
            assertBusy(() -> {
                // check all retention leases have been synced to all replicas
                for (final ShardRouting replicaShard : clusterService().state().routingTable().index("index").shard(0).replicaShards()) {
                    final String replicaShardNodeId = replicaShard.currentNodeId();
                    final String replicaShardNodeName = clusterService().state().nodes().get(replicaShardNodeId).getName();
                    final IndexShard replica = internalCluster()
                            .getInstance(IndicesService.class, replicaShardNodeName)
                            .getShardOrNull(new ShardId(resolveIndex("index"), 0));
                    assertThat(replica.getRetentionLeases(), equalTo(primary.getRetentionLeases()));
                }
            });
        }
    }

    public void testRetentionLeasesBackgroundSyncWithSoftDeletesDisabled() throws Exception {
        final int numberOfReplicas = 2 - scaledRandomIntBetween(0, 2);
        internalCluster().ensureAtLeastNumDataNodes(1 + numberOfReplicas);
        TimeValue syncIntervalSetting = TimeValue.timeValueMillis(between(1, 100));
        final Settings settings = Settings.builder()
            .put("index.number_of_shards", 1)
            .put("index.number_of_replicas", numberOfReplicas)
            .put(IndexService.RETENTION_LEASE_SYNC_INTERVAL_SETTING.getKey(), syncIntervalSetting.getStringRep())
            .put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), false)
            .build();
        createIndex("index", settings);
        final String primaryShardNodeId = clusterService().state().routingTable().index("index").shard(0).primaryShard().currentNodeId();
        final String primaryShardNodeName = clusterService().state().nodes().get(primaryShardNodeId).getName();
        final MockTransportService primaryTransportService = (MockTransportService) internalCluster().getInstance(
            TransportService.class, primaryShardNodeName);
        final AtomicBoolean backgroundSyncRequestSent = new AtomicBoolean();
        primaryTransportService.addSendBehavior((connection, requestId, action, request, options) -> {
            if (action.startsWith(RetentionLeaseBackgroundSyncAction.ACTION_NAME)) {
                backgroundSyncRequestSent.set(true);
            }
            connection.sendRequest(requestId, action, request, options);
        });
        final long start = System.nanoTime();
        ensureGreen("index");
        final long syncEnd = System.nanoTime();
        // We sleep long enough for the retention leases background sync to be triggered
        Thread.sleep(Math.max(0, randomIntBetween(2, 3) * syncIntervalSetting.millis() - TimeUnit.NANOSECONDS.toMillis(syncEnd - start)));
        assertFalse("retention leases background sync must be a noop if soft deletes is disabled", backgroundSyncRequestSent.get());
    }

    public void testRetentionLeasesSyncOnRecovery() throws Exception {
        final int numberOfReplicas = 2 - scaledRandomIntBetween(0, 2);
        internalCluster().ensureAtLeastNumDataNodes(1 + numberOfReplicas);
        /*
         * We effectively disable the background sync to ensure that the retention leases are not synced in the background so that the only
         * source of retention leases on the replicas would be from recovery.
         */
        final Settings.Builder settings = Settings.builder()
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", 0)
                .put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), true)
                .put(IndexService.RETENTION_LEASE_SYNC_INTERVAL_SETTING.getKey(), TimeValue.timeValueHours(24));
        // when we increase the number of replicas below we want to exclude the replicas from being allocated so that they do not recover
        assertAcked(prepareCreate("index", 1, settings));
        ensureYellow("index");
        final AcknowledgedResponse response = client().admin()
                .indices()
                .prepareUpdateSettings("index").setSettings(Settings.builder().put("index.number_of_replicas", numberOfReplicas).build())
                .get();
        assertTrue(response.isAcknowledged());
        final String primaryShardNodeId = clusterService().state().routingTable().index("index").shard(0).primaryShard().currentNodeId();
        final String primaryShardNodeName = clusterService().state().nodes().get(primaryShardNodeId).getName();
        final IndexShard primary = internalCluster()
                .getInstance(IndicesService.class, primaryShardNodeName)
                .getShardOrNull(new ShardId(resolveIndex("index"), 0));
        final int length = randomIntBetween(1, 8);
        final Map<String, RetentionLease> currentRetentionLeases = new LinkedHashMap<>();
        for (int i = 0; i < length; i++) {
            final String id = randomValueOtherThanMany(currentRetentionLeases.keySet()::contains, () -> randomAlphaOfLength(8));
            final long retainingSequenceNumber = randomLongBetween(0, Long.MAX_VALUE);
            final String source = randomAlphaOfLength(8);
            final CountDownLatch latch = new CountDownLatch(1);
            final ActionListener<ReplicationResponse> listener = ActionListener.wrap(r -> latch.countDown(), e -> fail(e.toString()));
            currentRetentionLeases.put(id, primary.addRetentionLease(id, retainingSequenceNumber, source, listener));
            latch.await();
            currentRetentionLeases.put(id, primary.renewRetentionLease(id, retainingSequenceNumber, source));
        }

        // now allow the replicas to be allocated and wait for recovery to finalize
        allowNodes("index", 1 + numberOfReplicas);
        ensureGreen("index");

        // check current retention leases have been synced to all replicas
        for (final ShardRouting replicaShard : clusterService().state().routingTable().index("index").shard(0).replicaShards()) {
            final String replicaShardNodeId = replicaShard.currentNodeId();
            final String replicaShardNodeName = clusterService().state().nodes().get(replicaShardNodeId).getName();
            final IndexShard replica = internalCluster()
                    .getInstance(IndicesService.class, replicaShardNodeName)
                    .getShardOrNull(new ShardId(resolveIndex("index"), 0));
            final Map<String, RetentionLease> retentionLeasesOnReplica = RetentionLeases.toMap(replica.getRetentionLeases());
            assertThat(retentionLeasesOnReplica, equalTo(currentRetentionLeases));

            // check retention leases have been written on the replica; see RecoveryTarget#finalizeRecovery
            assertThat(currentRetentionLeases, equalTo(RetentionLeases.toMap(replica.loadRetentionLeases())));
        }
    }

    public void testCanAddRetentionLeaseUnderBlock() throws InterruptedException {
        final String idForInitialRetentionLease = randomAlphaOfLength(8);
        runUnderBlockTest(
                idForInitialRetentionLease,
                randomLongBetween(0, Long.MAX_VALUE),
                (primary, listener) -> {
                    final String nextId = randomValueOtherThan(idForInitialRetentionLease, () -> randomAlphaOfLength(8));
                    final long nextRetainingSequenceNumber = randomLongBetween(0, Long.MAX_VALUE);
                    final String nextSource = randomAlphaOfLength(8);
                    primary.addRetentionLease(nextId, nextRetainingSequenceNumber, nextSource, listener);
                },
                primary -> {});
    }

    public void testCanRenewRetentionLeaseUnderBlock() throws InterruptedException {
        final String idForInitialRetentionLease = randomAlphaOfLength(8);
        final long initialRetainingSequenceNumber = randomLongBetween(0, Long.MAX_VALUE);
        final AtomicReference<RetentionLease> retentionLease = new AtomicReference<>();
        runUnderBlockTest(
                idForInitialRetentionLease,
                initialRetainingSequenceNumber,
                (primary, listener) -> {
                    final long nextRetainingSequenceNumber = randomLongBetween(initialRetainingSequenceNumber, Long.MAX_VALUE);
                    final String nextSource = randomAlphaOfLength(8);
                    retentionLease.set(primary.renewRetentionLease(idForInitialRetentionLease, nextRetainingSequenceNumber, nextSource));
                    listener.onResponse(new ReplicationResponse());
                },
                primary -> {
                    try {
                        /*
                         * If the background renew was able to execute, then the retention leases were persisted to disk. There is no other
                         * way for the current retention leases to end up written to disk so we assume that if they are written to disk, it
                         * implies that the background sync was able to execute under a block.
                         */
                        assertBusy(() -> assertThat(primary.loadRetentionLeases().leases(), contains(retentionLease.get())));
                    } catch (final Exception e) {
                        fail(e.toString());
                    }
                });

    }

    public void testCanRemoveRetentionLeasesUnderBlock() throws InterruptedException {
        final String idForInitialRetentionLease = randomAlphaOfLength(8);
        runUnderBlockTest(
                idForInitialRetentionLease,
                randomLongBetween(0, Long.MAX_VALUE),
                (primary, listener) -> primary.removeRetentionLease(idForInitialRetentionLease, listener),
                indexShard -> {});
    }

    private void runUnderBlockTest(
            final String idForInitialRetentionLease,
            final long initialRetainingSequenceNumber,
            final BiConsumer<IndexShard, ActionListener<ReplicationResponse>> primaryConsumer,
            final Consumer<IndexShard> afterSync) throws InterruptedException {
        final Settings settings = Settings.builder()
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", 0)
                .put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), true)
                .put(IndexService.RETENTION_LEASE_SYNC_INTERVAL_SETTING.getKey(), TimeValue.timeValueSeconds(1))
                .build();
        assertAcked(prepareCreate("index").setSettings(settings));
        ensureGreen("index");

        final String primaryShardNodeId = clusterService().state().routingTable().index("index").shard(0).primaryShard().currentNodeId();
        final String primaryShardNodeName = clusterService().state().nodes().get(primaryShardNodeId).getName();
        final IndexShard primary = internalCluster()
                .getInstance(IndicesService.class, primaryShardNodeName)
                .getShardOrNull(new ShardId(resolveIndex("index"), 0));

        final String source = randomAlphaOfLength(8);
        final CountDownLatch latch = new CountDownLatch(1);
        final ActionListener<ReplicationResponse> listener = ActionListener.wrap(r -> latch.countDown(), e -> fail(e.toString()));
        primary.addRetentionLease(idForInitialRetentionLease, initialRetainingSequenceNumber, source, listener);
        latch.await();

        final String block = randomFrom("read_only", "read_only_allow_delete", "read", "write", "metadata");

        client()
                .admin()
                .indices()
                .prepareUpdateSettings("index")
                .setSettings(Settings.builder().put("index.blocks." + block, true).build())
                .get();

        try {
            final CountDownLatch actionLatch = new CountDownLatch(1);
            final AtomicBoolean success = new AtomicBoolean();

            primaryConsumer.accept(
                    primary,
                    new ActionListener<ReplicationResponse>() {

                        @Override
                        public void onResponse(final ReplicationResponse replicationResponse) {
                            success.set(true);
                            actionLatch.countDown();
                        }

                        @Override
                        public void onFailure(final Exception e) {
                            fail(e.toString());
                        }

                    });
            actionLatch.await();
            assertTrue(success.get());
            afterSync.accept(primary);
        } finally {
            client()
                    .admin()
                    .indices()
                    .prepareUpdateSettings("index")
                    .setSettings(Settings.builder().putNull("index.blocks." + block).build())
                    .get();
        }
    }

    public void testCanAddRetentionLeaseWithoutWaitingForShards() throws InterruptedException {
        final String idForInitialRetentionLease = randomAlphaOfLength(8);
        runWaitForShardsTest(
                idForInitialRetentionLease,
                randomLongBetween(0, Long.MAX_VALUE),
                (primary, listener) -> {
                    final String nextId = randomValueOtherThan(idForInitialRetentionLease, () -> randomAlphaOfLength(8));
                    final long nextRetainingSequenceNumber = randomLongBetween(0, Long.MAX_VALUE);
                    final String nextSource = randomAlphaOfLength(8);
                    primary.addRetentionLease(nextId, nextRetainingSequenceNumber, nextSource, listener);
                },
                primary -> {});
    }

    public void testCanRenewRetentionLeaseWithoutWaitingForShards() throws InterruptedException {
        final String idForInitialRetentionLease = randomAlphaOfLength(8);
        final long initialRetainingSequenceNumber = randomLongBetween(0, Long.MAX_VALUE);
        final AtomicReference<RetentionLease> retentionLease = new AtomicReference<>();
        runWaitForShardsTest(
                idForInitialRetentionLease,
                initialRetainingSequenceNumber,
                (primary, listener) -> {
                    final long nextRetainingSequenceNumber = randomLongBetween(initialRetainingSequenceNumber, Long.MAX_VALUE);
                    final String nextSource = randomAlphaOfLength(8);
                    retentionLease.set(primary.renewRetentionLease(idForInitialRetentionLease, nextRetainingSequenceNumber, nextSource));
                    listener.onResponse(new ReplicationResponse());
                },
                primary -> {
                    try {
                        /*
                         * If the background renew was able to execute, then the retention leases were persisted to disk. There is no other
                         * way for the current retention leases to end up written to disk so we assume that if they are written to disk, it
                         * implies that the background sync was able to execute despite wait for shards being set on the index.
                         */
                        assertBusy(() -> assertThat(primary.loadRetentionLeases().leases(), contains(retentionLease.get())));
                    } catch (final Exception e) {
                        fail(e.toString());
                    }
                });

    }

    public void testCanRemoveRetentionLeasesWithoutWaitingForShards() throws InterruptedException {
        final String idForInitialRetentionLease = randomAlphaOfLength(8);
        runWaitForShardsTest(
                idForInitialRetentionLease,
                randomLongBetween(0, Long.MAX_VALUE),
                (primary, listener) -> primary.removeRetentionLease(idForInitialRetentionLease, listener),
                primary -> {});
    }

    private void runWaitForShardsTest(
            final String idForInitialRetentionLease,
            final long initialRetainingSequenceNumber,
            final BiConsumer<IndexShard, ActionListener<ReplicationResponse>> primaryConsumer,
            final Consumer<IndexShard> afterSync) throws InterruptedException {
        final int numDataNodes = internalCluster().numDataNodes();
        final Settings settings = Settings.builder()
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", numDataNodes)
                .put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), true)
                .put(IndexService.RETENTION_LEASE_SYNC_INTERVAL_SETTING.getKey(), TimeValue.timeValueSeconds(1))
                .build();
        assertAcked(prepareCreate("index").setSettings(settings));
        ensureYellowAndNoInitializingShards("index");
        assertFalse(client().admin().cluster().prepareHealth("index").setWaitForActiveShards(numDataNodes).get().isTimedOut());

        final String primaryShardNodeId = clusterService().state().routingTable().index("index").shard(0).primaryShard().currentNodeId();
        final String primaryShardNodeName = clusterService().state().nodes().get(primaryShardNodeId).getName();
        final IndexShard primary = internalCluster()
                .getInstance(IndicesService.class, primaryShardNodeName)
                .getShardOrNull(new ShardId(resolveIndex("index"), 0));

        final String source = randomAlphaOfLength(8);
        final CountDownLatch latch = new CountDownLatch(1);
        final ActionListener<ReplicationResponse> listener = ActionListener.wrap(r -> latch.countDown(), e -> fail(e.toString()));
        primary.addRetentionLease(idForInitialRetentionLease, initialRetainingSequenceNumber, source, listener);
        latch.await();

        final String waitForActiveValue = randomBoolean() ? "all" : Integer.toString(numDataNodes + 1);

        client()
                .admin()
                .indices()
                .prepareUpdateSettings("index")
                .setSettings(Settings.builder().put("index.write.wait_for_active_shards", waitForActiveValue).build())
                .get();

        final CountDownLatch actionLatch = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean();

        primaryConsumer.accept(
                primary,
                new ActionListener<ReplicationResponse>() {

                    @Override
                    public void onResponse(final ReplicationResponse replicationResponse) {
                        success.set(true);
                        actionLatch.countDown();
                    }

                    @Override
                    public void onFailure(final Exception e) {
                        fail(e.toString());
                    }

                });
        actionLatch.await();
        assertTrue(success.get());
        afterSync.accept(primary);
    }

}
