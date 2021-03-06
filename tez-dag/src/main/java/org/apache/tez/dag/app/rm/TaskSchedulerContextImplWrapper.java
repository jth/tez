/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tez.dag.app.rm;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.yarn.api.records.ApplicationAccessType;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.tez.common.ContainerSignatureMatcher;
import org.apache.tez.dag.api.TezUncheckedException;
import org.apache.tez.dag.api.UserPayload;
import org.apache.tez.serviceplugins.api.TaskSchedulerContext;

/**
 * Makes use of an ExecutionService to invoke application callbacks. Methods
 * which return values wait for execution to complete - effectively waiting for
 * all previous events in the queue to complete.
 */
class TaskSchedulerContextImplWrapper implements TaskSchedulerContext {

  private TaskSchedulerContext real;

  private ExecutorService executorService;
  
  /**
   * @param real the actual TaskSchedulerAppCallback
   * @param executorService the ExecutorService to be used to send these events.
   */
  public TaskSchedulerContextImplWrapper(TaskSchedulerContext real,
                                         ExecutorService executorService) {
    this.real = real;
    this.executorService = executorService;
  }

  @Override
  public void taskAllocated(Object task, Object appCookie, Container container) {
    executorService.submit(new TaskAllocatedCallable(real, task, appCookie,
        container));
  }

  @Override
  public void containerCompleted(Object taskLastAllocated,
      ContainerStatus containerStatus) {
    executorService.submit(new ContainerCompletedCallable(real,
        taskLastAllocated, containerStatus));
  }

  @Override
  public void containerBeingReleased(ContainerId containerId) {
    executorService
        .submit(new ContainerBeingReleasedCallable(real, containerId));
  }

  @Override
  public void nodesUpdated(List<NodeReport> updatedNodes) {
    executorService.submit(new NodesUpdatedCallable(real, updatedNodes));
  }

  @Override
  public void appShutdownRequested() {
    executorService.submit(new AppShudownRequestedCallable(real));
  }

  @Override
  public void setApplicationRegistrationData(Resource maxContainerCapability,
      Map<ApplicationAccessType, String> appAcls, ByteBuffer key) {
    executorService.submit(new SetApplicationRegistrationDataCallable(real,
        maxContainerCapability, appAcls, key));
  }

  @Override
  public void onError(Throwable t) {
    executorService.submit(new OnErrorCallable(real, t));
  }

  @Override
  public float getProgress() {
    Future<Float> progressFuture = executorService
        .submit(new GetProgressCallable(real));
    try {
      return progressFuture.get();
    } catch (Exception e) {
      throw new TezUncheckedException(e);
    }
  }
  
  @Override
  public void preemptContainer(ContainerId containerId) {
    executorService.submit(new PreemptContainerCallable(real, containerId));
  }

  @Override
  public AppFinalStatus getFinalAppStatus() {
    Future<AppFinalStatus> appFinalStatusFuture = executorService
        .submit(new GetFinalAppStatusCallable(real));
    try {
      return appFinalStatusFuture.get();
    } catch (Exception e) {
      throw new TezUncheckedException(e);
    }
  }

  // Getters which do not need to go through a thread. Underlying implementation
  // does not use locks.

  @Override
  public UserPayload getInitialUserPayload() {
    return real.getInitialUserPayload();
  }

  @Override
  public String getAppTrackingUrl() {
    return real.getAppTrackingUrl();
  }

  @Override
  public long getCustomClusterIdentifier() {
    return real.getCustomClusterIdentifier();
  }

  @Override
  public ContainerSignatureMatcher getContainerSignatureMatcher() {
    return real.getContainerSignatureMatcher();
  }

  @Override
  public ApplicationAttemptId getApplicationAttemptId() {
    return real.getApplicationAttemptId();
  }

  @Override
  public String getAppHostName() {
    return real.getAppHostName();
  }

  @Override
  public int getAppClientPort() {
    return real.getAppClientPort();
  }

  @Override
  public boolean isSession() {
    return real.isSession();
  }

  @Override
  public AMState getAMState() {
    return real.getAMState();
  }
  // End of getters which do not need to go through a thread. Underlying implementation
  // does not use locks.


  static abstract class TaskSchedulerContextCallbackBase {

    protected TaskSchedulerContext app;

    public TaskSchedulerContextCallbackBase(TaskSchedulerContext app) {
      this.app = app;
    }
  }

  static class TaskAllocatedCallable extends TaskSchedulerContextCallbackBase
      implements Callable<Void> {
    private final Object task;
    private final Object appCookie;
    private final Container container;

    public TaskAllocatedCallable(TaskSchedulerContext app, Object task,
        Object appCookie, Container container) {
      super(app);
      this.task = task;
      this.appCookie = appCookie;
      this.container = container;
    }

    @Override
    public Void call() throws Exception {
      app.taskAllocated(task, appCookie, container);
      return null;
    }
  }

  static class ContainerCompletedCallable extends TaskSchedulerContextCallbackBase
      implements Callable<Void> {

    private final Object taskLastAllocated;
    private final ContainerStatus containerStatus;

    public ContainerCompletedCallable(TaskSchedulerContext app,
        Object taskLastAllocated, ContainerStatus containerStatus) {
      super(app);
      this.taskLastAllocated = taskLastAllocated;
      this.containerStatus = containerStatus;
    }

    @Override
    public Void call() throws Exception {
      app.containerCompleted(taskLastAllocated, containerStatus);
      return null;
    }
  }

  static class ContainerBeingReleasedCallable extends
      TaskSchedulerContextCallbackBase implements Callable<Void> {
    private final ContainerId containerId;

    public ContainerBeingReleasedCallable(TaskSchedulerContext app,
        ContainerId containerId) {
      super(app);
      this.containerId = containerId;
    }

    @Override
    public Void call() throws Exception {
      app.containerBeingReleased(containerId);
      return null;
    }
  }

  static class NodesUpdatedCallable extends TaskSchedulerContextCallbackBase
      implements Callable<Void> {
    private final List<NodeReport> updatedNodes;

    public NodesUpdatedCallable(TaskSchedulerContext app,
        List<NodeReport> updatedNodes) {
      super(app);
      this.updatedNodes = updatedNodes;
    }

    @Override
    public Void call() throws Exception {
      app.nodesUpdated(updatedNodes);
      return null;
    }
  }

  static class AppShudownRequestedCallable extends TaskSchedulerContextCallbackBase
      implements Callable<Void> {

    public AppShudownRequestedCallable(TaskSchedulerContext app) {
      super(app);
    }

    @Override
    public Void call() throws Exception {
      app.appShutdownRequested();
      return null;
    }
  }

  static class SetApplicationRegistrationDataCallable extends
      TaskSchedulerContextCallbackBase implements Callable<Void> {

    private final Resource maxContainerCapability;
    private final Map<ApplicationAccessType, String> appAcls;
    private final ByteBuffer key;

    public SetApplicationRegistrationDataCallable(TaskSchedulerContext app,
        Resource maxContainerCapability,
        Map<ApplicationAccessType, String> appAcls,
        ByteBuffer key) {
      super(app);
      this.maxContainerCapability = maxContainerCapability;
      this.appAcls = appAcls;
      this.key = key;
    }

    @Override
    public Void call() throws Exception {
      app.setApplicationRegistrationData(maxContainerCapability, appAcls, key);
      return null;
    }
  }

  static class OnErrorCallable extends TaskSchedulerContextCallbackBase implements
      Callable<Void> {

    private final Throwable throwable;

    public OnErrorCallable(TaskSchedulerContext app, Throwable throwable) {
      super(app);
      this.throwable = throwable;
    }

    @Override
    public Void call() throws Exception {
      app.onError(throwable);
      return null;
    }
  }

  static class PreemptContainerCallable extends TaskSchedulerContextCallbackBase
      implements Callable<Void> {
    private final ContainerId containerId;
    
    public PreemptContainerCallable(TaskSchedulerContext app, ContainerId id) {
      super(app);
      this.containerId = id;
    }
    
    @Override
    public Void call() throws Exception {
      app.preemptContainer(containerId);
      return null;
    }
  }
  
  static class GetProgressCallable extends TaskSchedulerContextCallbackBase
      implements Callable<Float> {

    public GetProgressCallable(TaskSchedulerContext app) {
      super(app);
    }

    @Override
    public Float call() throws Exception {
      return app.getProgress();
    }
  }

  static class GetFinalAppStatusCallable extends TaskSchedulerContextCallbackBase
      implements Callable<AppFinalStatus> {

    public GetFinalAppStatusCallable(TaskSchedulerContext app) {
      super(app);
    }

    @Override
    public AppFinalStatus call() throws Exception {
      return app.getFinalAppStatus();
    }
  }

  @VisibleForTesting
  @InterfaceAudience.Private
  ExecutorService getExecutorService() {
    return executorService;
  }
}
