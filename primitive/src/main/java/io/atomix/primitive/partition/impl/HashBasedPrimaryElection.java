/*
 * Copyright 2018-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.primitive.partition.impl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import io.atomix.cluster.ClusterMembershipService;
import io.atomix.cluster.Member;
import io.atomix.cluster.MemberEvent;
import io.atomix.cluster.MemberId;
import io.atomix.cluster.messaging.ClusterCommunicationService;
import io.atomix.primitive.partition.GroupMember;
import io.atomix.primitive.partition.PartitionGroupMembership;
import io.atomix.primitive.partition.PartitionGroupMembershipEvent;
import io.atomix.primitive.partition.PartitionGroupMembershipEventListener;
import io.atomix.primitive.partition.PartitionGroupMembershipService;
import io.atomix.primitive.partition.PartitionId;
import io.atomix.primitive.partition.PrimaryElection;
import io.atomix.primitive.partition.PrimaryElectionEvent;
import io.atomix.primitive.partition.PrimaryTerm;
import io.atomix.utils.serializer.Namespace;
import io.atomix.utils.serializer.Namespaces;
import io.atomix.utils.serializer.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hash-based primary election.
 */
public class HashBasedPrimaryElection implements PrimaryElection {
  private static final Logger LOGGER = LoggerFactory.getLogger(HashBasedPrimaryElection.class);
  private static final long BROADCAST_INTERVAL = 5000;

  private static final Serializer SERIALIZER = Serializer.using(Namespace.builder()
      .register(Namespaces.BASIC)
      .register(MemberId.class)
      .build());

  private final PartitionId partitionId;
  private final ClusterMembershipService clusterMembershipService;
  private final PartitionGroupMembershipService groupMembershipService;
  private final ClusterCommunicationService communicationService;
  private final Consumer<MemberEvent> clusterMembershipEventListener = this::handleClusterMembershipEvent;
  private final Set<Consumer<PrimaryElectionEvent>> listeners = new CopyOnWriteArraySet<>();
  private final Map<MemberId, Integer> counters = Maps.newConcurrentMap();
  private final String subject;
  private final ScheduledFuture<?> broadcastFuture;
  private volatile PrimaryTerm currentTerm;

  private final PartitionGroupMembershipEventListener groupMembershipEventListener = new PartitionGroupMembershipEventListener() {
    @Override
    public void event(PartitionGroupMembershipEvent event) {
      recomputeTerm(event.membership());
    }

    @Override
    public boolean isRelevant(PartitionGroupMembershipEvent event) {
      return event.membership().group().equals(partitionId.getGroup());
    }
  };

  public HashBasedPrimaryElection(
      PartitionId partitionId,
      ClusterMembershipService clusterMembershipService,
      PartitionGroupMembershipService groupMembershipService,
      ClusterCommunicationService communicationService,
      ScheduledExecutorService executor) {
    this.partitionId = partitionId;
    this.clusterMembershipService = clusterMembershipService;
    this.groupMembershipService = groupMembershipService;
    this.communicationService = communicationService;
    this.subject = String.format("primary-election-counter-%s-%d", partitionId.getGroup(), partitionId.getPartition());
    recomputeTerm(groupMembershipService.getMembership(partitionId.getGroup()));
    groupMembershipService.addListener(groupMembershipEventListener);
    clusterMembershipService.addListener(clusterMembershipEventListener);
    communicationService.subscribe(subject, SERIALIZER::decode, this::updateCounters, executor);
    broadcastFuture = executor.scheduleAtFixedRate(this::broadcastCounters, BROADCAST_INTERVAL, BROADCAST_INTERVAL, TimeUnit.MILLISECONDS);
  }

  @Override
  public CompletableFuture<PrimaryTerm> enter(GroupMember member) {
    return CompletableFuture.completedFuture(currentTerm);
  }

  @Override
  public CompletableFuture<PrimaryTerm> getTerm() {
    return CompletableFuture.completedFuture(currentTerm);
  }

  @Override
  public CompletableFuture<Void> addListener(Consumer<PrimaryElectionEvent> listener) {
    listeners.add(listener);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<Void> removeListener(Consumer<PrimaryElectionEvent> listener) {
    listeners.remove(listener);
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Handles a cluster membership event.
   */
  private void handleClusterMembershipEvent(MemberEvent event) {
    if (event.getType() == MemberEvent.Type.ADDED || event.getType() == MemberEvent.Type.REMOVED) {
      recomputeTerm(groupMembershipService.getMembership(partitionId.getGroup()));
    }
  }

  /**
   * Returns the current term.
   *
   * @return the current term
   */
  private long currentTerm() {
    return counters.values().stream().mapToInt(v -> v).sum();
  }

  /**
   * Increments and returns the current term.
   *
   * @return the current term
   */
  private long incrementTerm() {
    counters.compute(MemberId.from(clusterMembershipService.getLocalMember()), (id, value) -> value != null ? value + 1 : 1);
    broadcastCounters();
    return currentTerm();
  }

  private void updateCounters(Map<MemberId, Integer> counters) {
    for (Map.Entry<MemberId, Integer> entry : counters.entrySet()) {
      this.counters.compute(entry.getKey(), (key, value) -> {
        if (value == null || value < entry.getValue()) {
          return entry.getValue();
        }
        return value;
      });
    }
    updateTerm(currentTerm());
  }

  private void broadcastCounters() {
    communicationService.broadcast(subject, counters, SERIALIZER::encode);
  }

  private void updateTerm(long term) {
    if (term > currentTerm.getTerm()) {
      recomputeTerm(groupMembershipService.getMembership(partitionId.getGroup()));
    }
  }

  /**
   * Recomputes the current term.
   */
  private synchronized void recomputeTerm(PartitionGroupMembership membership) {
    if (membership == null) {
      return;
    }

    // Create a list of candidates based on the availability of members in the group.
    List<GroupMember> candidates = new ArrayList<>();
    for (MemberId memberId : membership.members()) {
      Member member = clusterMembershipService.getMember(memberId);
      if (member != null && member.getState() != Member.State.DEAD) {
        candidates.add(GroupMember.newBuilder()
            .setMemberId(memberId.toString())
            .setMemberGroupId(memberId.toString())
            .build());
      }
    }

    // Sort the candidates by a hash of their member ID.
    candidates.sort((a, b) -> {
      int aoffset = Hashing.murmur3_32().hashString(a.getMemberId(), StandardCharsets.UTF_8).asInt() % partitionId.getPartition();
      int boffset = Hashing.murmur3_32().hashString(b.getMemberId(), StandardCharsets.UTF_8).asInt() % partitionId.getPartition();
      return aoffset - boffset;
    });

    // Store the current term in a local variable avoid repeated volatile reads.
    PrimaryTerm currentTerm = this.currentTerm;

    // Compute the primary from the sorted candidates list.
    GroupMember primary = candidates.isEmpty() ? null : candidates.get(0);

    // Remove the primary from the candidates list.
    candidates = candidates.isEmpty() ? Collections.emptyList() : candidates.subList(1, candidates.size());

    // If the primary has changed, increment the term. Otherwise, use the current term from the replicated counter.
    long term = currentTerm != null
        && Objects.equals(currentTerm.getPrimary(), primary)
        && Objects.equals(currentTerm.getCandidatesList(), candidates)
        ? currentTerm() : incrementTerm();

    // Create the new primary term. If the term has changed update the term and trigger an event.
    PrimaryTerm newTerm = PrimaryTerm.newBuilder()
        .setTerm(term)
        .setPrimary(primary)
        .addAllCandidates(candidates)
        .build();
    if (!Objects.equals(currentTerm, newTerm)) {
      this.currentTerm = newTerm;
      LOGGER.debug("{} - Recomputed term for partition {}: {}", MemberId.from(clusterMembershipService.getLocalMember()), partitionId, newTerm);
      listeners.forEach(l -> l.accept(PrimaryElectionEvent.newBuilder()
          .setPartitionId(partitionId)
          .setTerm(newTerm)
          .build()));
      broadcastCounters();
    }
  }

  /**
   * Closes the election.
   */
  void close() {
    broadcastFuture.cancel(false);
    groupMembershipService.removeListener(groupMembershipEventListener);
    clusterMembershipService.removeListener(clusterMembershipEventListener);
  }
}
